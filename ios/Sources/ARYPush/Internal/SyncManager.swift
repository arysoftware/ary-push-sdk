import Foundation

/// Turns local state changes into backend calls that eventually happen.
///
/// The contract with the rest of the SDK is one-directional: managers change local state first
/// and tell the sync manager afterwards. Nothing in the push path ever awaits this class, which
/// is what makes a backend outage invisible to notification delivery.
///
/// Drain rules:
///
/// * Operations run in dependency order, never before the installation they describe has been
///   registered.
/// * A failure stops the drain rather than skipping ahead, so a later operation can never
///   overtake the one it depends on.
/// * Transient failures are retried with jittered backoff and a persisted attempt count.
///   Permanent failures, and operations that exhaust their attempts, are dropped: a queue that
///   retries a 422 forever is a queue that never drains again.
final class SyncManager {

    private let queue: PendingOperationQueue
    private let storage: StorageManager
    private let isOnline: () -> Bool
    private let retryManager: RetryManager
    private let backendProvider: () -> PushBackend
    private let installationProvider: () -> Installation?

    private let lock = NSLock()
    private var drainTask: Task<Void, Never>?

    init(
        queue: PendingOperationQueue,
        storage: StorageManager,
        isOnline: @escaping () -> Bool,
        retryManager: RetryManager,
        backendProvider: @escaping () -> PushBackend,
        installationProvider: @escaping () -> Installation?
    ) {
        self.queue = queue
        self.storage = storage
        self.isOnline = isOnline
        self.retryManager = retryManager
        self.backendProvider = backendProvider
        self.installationProvider = installationProvider
    }

    // MARK: - Entry points

    func enqueueTokenUpdate(token: String, provider: PushProvider) {
        enqueue(
            PendingOperation(
                type: .updateToken,
                payload: [
                    PendingOperation.Key.token: token,
                    PendingOperation.Key.provider: provider.wireValue
                ]
            )
        )
    }

    func enqueueIdentify(userId: String) {
        enqueue(
            PendingOperation(type: .identifyUser, payload: [PendingOperation.Key.userId: userId])
        )
    }

    func enqueueLogout() {
        enqueue(PendingOperation(type: .logoutUser))
    }

    func enqueueTagUpdate(_ tags: [String: String]) {
        guard !tags.isEmpty else { return }
        enqueue(
            PendingOperation(
                type: .updateTags,
                payload: [PendingOperation.Key.tags: queue.encodeTags(tags)]
            )
        )
    }

    func enqueueTagRemoval(keys: Set<String>, all: Bool) {
        enqueue(
            PendingOperation(
                type: .removeTags,
                payload: [
                    PendingOperation.Key.tagKeys: keys.sorted().joined(separator: ","),
                    PendingOperation.Key.removeAll: String(all)
                ]
            )
        )
    }

    func enqueueTopicUpdate(_ topics: Set<String>) {
        enqueue(
            PendingOperation(
                type: .updateTopics,
                payload: [PendingOperation.Key.topics: topics.sorted().joined(separator: ",")]
            )
        )
    }

    func enqueuePermissionUpdate(enabled: Bool) {
        enqueue(
            PendingOperation(
                type: .updatePermission,
                payload: [PendingOperation.Key.enabled: String(enabled)]
            )
        )
    }

    func enqueueEvent(_ event: PushEvent) {
        enqueue(
            PendingOperation(
                type: .trackEvents,
                payload: [PendingOperation.Key.events: encodeEvents([event])]
            )
        )
    }

    /// Registers the installation when its contents actually changed.
    ///
    /// Repeated launches with identical data are the common case, and re-registering on every
    /// cold start would multiply the backend's write load by the number of app opens for no
    /// information gain.
    func registerInstallationIfChanged(force: Bool = false) {
        guard let installation = installationProvider() else { return }
        let hash = registrationHash(installation)
        if !force, storage.string(StorageManager.Keys.registrationHash) == hash {
            PushLogger.debug("Installation registration unchanged; skipping request")
            requestSync()
            return
        }
        enqueue(PendingOperation(type: .registerInstallation))
    }

    /// Adds an operation and asks the queue to drain.
    func enqueue(_ operation: PendingOperation) {
        queue.enqueue(operation)
        PushLogger.debug("Queued \(operation.type.rawValue) (pending=\(queue.count))")
        requestSync()
    }

    /// Drains the queue if it is not already draining. Safe to call from anywhere, at any time.
    func requestSync() {
        guard !queue.isEmpty else { return }

        lock.lock()
        if let existing = drainTask, !existing.isCancelled {
            lock.unlock()
            return
        }
        let task = Task { [weak self] in
            await self?.drain()
            self?.clearDrainTask()
        }
        drainTask = task
        lock.unlock()
    }

    /// Clears deferred work. Used by tests and by the documented device-reset support path.
    func reset() {
        queue.clear()
        storage.remove(StorageManager.Keys.registrationHash)
    }

    private func clearDrainTask() {
        lock.lock()
        drainTask = nil
        lock.unlock()
    }
}

// MARK: - Drain loop

private extension SyncManager {

    func drain() async {
        while !Task.isCancelled {
            let pending = queue.snapshot()
            guard let operation = pending.first else { return }

            guard isOnline() else {
                // Nothing is lost: the queue is durable and the next SDK call, or the next
                // launch, drains it.
                PushLogger.debug("Offline; \(pending.count) operation(s) stay queued")
                return
            }

            let outcome = await execute(operation)

            if outcome.isSuccess {
                queue.remove(id: operation.id)
                PushLogger.debug("Synced \(operation.type.rawValue)")
                continue
            }

            guard outcome.isRetryable else {
                queue.remove(id: operation.id)
                PushLogger.warn(
                    "Dropping \(operation.type.rawValue): permanent failure \(describe(outcome))"
                )
                continue
            }

            queue.recordAttempt(id: operation.id)
            let attempts = operation.attempts + 1
            guard retryManager.canRetry(attempt: attempts) else {
                queue.remove(id: operation.id)
                PushLogger.warn(
                    "Dropping \(operation.type.rawValue) after \(attempts) attempt(s): "
                        + describe(outcome)
                )
                continue
            }

            var retryAfter: TimeInterval?
            if case .failure(let error) = outcome { retryAfter = error.retryAfter }
            let wait = retryManager.delay(attempt: attempts, retryAfter: retryAfter)
            PushLogger.debug("Retrying \(operation.type.rawValue) (attempt \(attempts))")
            do {
                try await Task.sleep(nanoseconds: UInt64(max(0, wait) * 1_000_000_000))
            } catch {
                return
            }
            // Ordering matters, so the drain waits for this operation rather than moving on to
            // one that may depend on it.
        }
    }
}

// MARK: - Operation execution

private extension SyncManager {

    func execute(_ operation: PendingOperation) async -> ApiResult<Void> {
        guard let installation = installationProvider() else {
            return .failure(
                ApiError(
                    statusCode: nil,
                    code: "no_installation",
                    message: "Installation is not available yet"
                )
            )
        }
        let backend = backendProvider()

        switch operation.type {
        case .registerInstallation:
            let result = await backend.registerInstallation(installation)
            if result.isSuccess {
                storage.set(StorageManager.Keys.registrationHash, registrationHash(installation))
            }
            return result

        case .updateToken:
            guard let token = operation.payload[PendingOperation.Key.token], !token.isEmpty else {
                return .failure(
                    ApiError(
                        statusCode: nil,
                        code: "missing_token",
                        message: "Queued token update had no token"
                    )
                )
            }
            let provider = PushProvider.fromWire(operation.payload[PendingOperation.Key.provider])
            return await registered(installation, backend) {
                await backend.updateToken(
                    installationId: installation.id,
                    token: token,
                    provider: provider
                )
            }

        case .identifyUser:
            guard let userId = operation.payload[PendingOperation.Key.userId], !userId.isEmpty
            else {
                return .failure(
                    ApiError(
                        statusCode: nil,
                        code: "missing_user",
                        message: "Queued identify had no user id"
                    )
                )
            }
            return await registered(installation, backend) {
                await backend.identify(installationId: installation.id, userId: userId)
            }

        case .logoutUser:
            return await registered(installation, backend) {
                await backend.logout(installationId: installation.id)
            }

        case .updateTags:
            let tags = queue.decodeTags(operation.payload[PendingOperation.Key.tags])
            guard !tags.isEmpty else { return .success((), statusCode: 200) }
            return await registered(installation, backend) {
                await backend.updateTags(installationId: installation.id, tags: tags)
            }

        case .removeTags:
            let all = operation.payload[PendingOperation.Key.removeAll] == "true"
            let keys = Set(
                (operation.payload[PendingOperation.Key.tagKeys] ?? "")
                    .split(separator: ",").map(String.init)
            )
            return await registered(installation, backend) {
                await backend.removeTags(installationId: installation.id, keys: keys, all: all)
            }

        case .updateTopics:
            let topics = Set(
                (operation.payload[PendingOperation.Key.topics] ?? "")
                    .split(separator: ",").map(String.init)
            )
            return await registered(installation, backend) {
                await backend.updateTopics(installationId: installation.id, topics: topics)
            }

        case .updatePermission:
            let enabled = operation.payload[PendingOperation.Key.enabled] == "true"
            return await registered(installation, backend) {
                await backend.updateNotificationPermission(
                    installationId: installation.id,
                    enabled: enabled
                )
            }

        case .trackEvents:
            let events = decodeEvents(operation.payload[PendingOperation.Key.events])
            guard !events.isEmpty else { return .success((), statusCode: 200) }
            return await registered(installation, backend) {
                await backend.trackEvents(installationId: installation.id, events: events)
            }
        }
    }

    /// Guarantees the installation exists before a dependent write is attempted.
    ///
    /// The queue already orders registration first, but an installation can also be created by a
    /// background message long before the queue is touched, so the invariant is enforced here as
    /// well rather than assumed.
    func registered(
        _ installation: Installation,
        _ backend: PushBackend,
        _ block: () async -> ApiResult<Void>
    ) async -> ApiResult<Void> {
        let expected = registrationHash(installation)
        if storage.string(StorageManager.Keys.registrationHash) == expected {
            return await block()
        }

        let registration = await backend.registerInstallation(installation)
        guard registration.isSuccess else {
            PushLogger.debug("Deferring dependent operation: registration \(describe(registration))")
            return registration
        }
        storage.set(StorageManager.Keys.registrationHash, expected)
        return await block()
    }
}

// MARK: - Helpers

private extension SyncManager {

    /// Identity of a registration payload.
    ///
    /// Only the fields the backend stores contribute, so a locale change re-registers but a new
    /// timestamp does not.
    func registrationHash(_ installation: Installation) -> String {
        let parts: [String] = [
            installation.id,
            installation.applicationId ?? "",
            installation.provider.wireValue,
            installation.pushToken ?? "",
            installation.userId ?? "",
            installation.appVersion ?? "",
            installation.appBuild ?? "",
            installation.sdkVersion,
            installation.osVersion ?? "",
            installation.deviceModel ?? "",
            installation.locale ?? "",
            installation.timezone ?? "",
            String(installation.notificationsEnabled)
        ]
        // A stable digest rather than `hashValue`, which is seeded per process and would make
        // every launch look like a changed registration.
        return StableHash.digest(parts.joined(separator: "|"))
    }

    func encodeEvents(_ events: [PushEvent]) -> String {
        let payload = events.map { event in
            [
                "name": event.name,
                "occurredAt": event.occurredAt.timeIntervalSince1970,
                "properties": event.properties
            ] as [String: Any]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let encoded = String(data: data, encoding: .utf8)
        else { return "[]" }
        return encoded
    }

    func decodeEvents(_ raw: String?) -> [PushEvent] {
        guard let raw, let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return [] }

        return array.compactMap { entry in
            guard let name = entry["name"] as? String, !name.isEmpty else { return nil }
            let occurredAt = (entry["occurredAt"] as? TimeInterval)
                .map(Date.init(timeIntervalSince1970:)) ?? Date()
            return PushEvent(
                name: name,
                properties: entry["properties"] as? [String: String] ?? [:],
                occurredAt: occurredAt
            )
        }
    }

    func describe<T>(_ result: ApiResult<T>) -> String {
        switch result {
        case .success(_, let status, _):
            return "success \(status)"
        case .failure(let error):
            let status = error.statusCode.map(String.init) ?? "?"
            return "HTTP \(status) \(error.code ?? "") \(error.message ?? "")"
        case .networkError(let error):
            return "transport \(error)"
        }
    }
}
