import Foundation

/// The SDK's REST transport.
///
/// Responsibilities stop at HTTP: URLs, headers, JSON, timeouts, cancellation, retry
/// classification and a single authenticated retry after a 401. It knows nothing about
/// installations or tags, so push logic can be tested without a socket and swapped without
/// touching the notification engine.
///
/// TLS is left entirely at `URLSession`'s defaults. The SDK installs no
/// `URLSessionDelegate` challenge handler, never trusts an arbitrary certificate and never
/// disables ATS: an SDK that ships an SSL bypass ships it to every application that embeds it.
final class URLSessionRestClient: RestClient {

    private let backendConfig: PushBackendConfig
    private let retryManager: RetryManager
    private let authProvider: AuthProvider?
    private let device: DeviceInfoProvider
    private let installationIdProvider: () -> String?
    private let session: URLSession
    private let ownsSession: Bool

    init(
        backendConfig: PushBackendConfig,
        networkConfig: NetworkConfig,
        retryConfig: RetryConfig,
        authProvider: AuthProvider?,
        device: DeviceInfoProvider,
        installationIdProvider: @escaping () -> String?,
        session: URLSession? = nil
    ) {
        self.backendConfig = backendConfig
        self.retryManager = RetryManager(config: retryConfig)
        self.authProvider = authProvider
        self.device = device
        self.installationIdProvider = installationIdProvider

        if let session {
            self.session = session
            self.ownsSession = false
        } else {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.timeoutIntervalForRequest = networkConfig.requestTimeout
            configuration.timeoutIntervalForResource = networkConfig.resourceTimeout
            configuration.waitsForConnectivity = false
            configuration.httpAdditionalHeaders = nil
            self.session = URLSession(configuration: configuration)
            self.ownsSession = true
        }

        if backendConfig.isPlaintext {
            PushLogger.warn(
                "Backend base URL uses plaintext HTTP. This is acceptable only for local "
                    + "development; production traffic must use HTTPS."
            )
        }
    }

    // MARK: - RestClient

    func get<T>(
        path: String,
        query: [String: Any?],
        headers: [String: String],
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T> {
        await send(method: "GET", path: path, query: query, body: nil, extra: headers, parse: parse)
    }

    func post<T>(
        path: String,
        body: Any?,
        headers: [String: String],
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T> {
        await send(method: "POST", path: path, query: [:], body: body, extra: headers, parse: parse)
    }

    func put<T>(
        path: String,
        body: Any?,
        headers: [String: String],
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T> {
        await send(method: "PUT", path: path, query: [:], body: body, extra: headers, parse: parse)
    }

    func patch<T>(
        path: String,
        body: Any?,
        headers: [String: String],
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T> {
        await send(method: "PATCH", path: path, query: [:], body: body, extra: headers, parse: parse)
    }

    func delete(
        path: String,
        query: [String: Any?],
        headers: [String: String]
    ) async -> ApiResult<Void> {
        await send(
            method: "DELETE",
            path: path,
            query: query,
            body: nil,
            extra: headers,
            parse: ignoreBody
        )
    }

    func close() {
        guard ownsSession else { return }
        session.invalidateAndCancel()
    }
}

// MARK: - Request pipeline

private extension URLSessionRestClient {

    func send<T>(
        method: String,
        path: String,
        query: [String: Any?],
        body: Any?,
        extra: [String: String],
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T> {
        guard let url = buildURL(path: path, query: query) else {
            return .failure(
                ApiError(
                    statusCode: nil,
                    code: "invalid_url",
                    message: "Could not build a URL from base=\(backendConfig.normalizedBaseURL) path=\(path)"
                )
            )
        }

        let requestId = UUID().uuidString
        var attempt = 1
        var refreshedOnce = false

        while true {
            if Task.isCancelled { return .networkError(CancellationError()) }

            // The auth header is rebuilt on every attempt so a token refreshed between attempts
            // is actually used.
            let token = await currentAccessToken()
            var request = URLRequest(url: url)
            request.httpMethod = method
            for (name, value) in buildHeaders(extra: extra, requestId: requestId, token: token) {
                request.setValue(value, forHTTPHeaderField: name)
            }
            if let encoded = encodeBody(body, method: method) {
                request.httpBody = encoded
                request.setValue(Self.jsonContentType, forHTTPHeaderField: "Content-Type")
            }

            PushLogger.debug(
                "\(method) \(url.path) attempt=\(attempt)/\(retryManager.maxAttempts) "
                    + "requestId=\(requestId)"
            )

            let result: ApiResult<T>
            do {
                let (data, response) = try await perform(request)
                result = interpret(data: data, response: response, parse: parse)
            } catch is CancellationError {
                // Cancellation is the caller's decision, never a transport failure.
                return .networkError(CancellationError())
            } catch {
                result = .networkError(error)
            }

            // A single, non-recursive refresh attempt that does not consume a retry attempt and
            // can only ever happen once per request.
            if case .failure(let error) = result,
               error.statusCode == 401,
               !refreshedOnce,
               let authProvider {
                refreshedOnce = true
                let refreshed = await authProvider.refreshAccessToken()
                PushLogger.debug("Received 401; refresh \(refreshed ? "succeeded" : "failed")")
                if refreshed { continue }
            }

            guard result.isRetryable, retryManager.canRetry(attempt: attempt) else {
                logOutcome(method: method, url: url, requestId: requestId, attempts: attempt, result: result)
                return result
            }

            var retryAfter: TimeInterval?
            if case .failure(let error) = result { retryAfter = error.retryAfter }
            let wait = retryManager.delay(attempt: attempt, retryAfter: retryAfter)
            PushLogger.debug("Retrying \(method) \(url.path) in \(String(format: "%.2f", wait))s")

            do {
                try await Task.sleep(nanoseconds: UInt64(max(0, wait) * 1_000_000_000))
            } catch {
                return .networkError(CancellationError())
            }
            attempt += 1
        }
    }

    /// Bridges `URLSessionDataTask` to `async`, propagating task cancellation to the transport.
    ///
    /// `URLSession.data(for:)` would be simpler but is iOS 15+, and the SDK supports iOS 13.
    func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let box = TaskBox()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let task = session.dataTask(with: request) { data, response, error in
                    if let error {
                        continuation.resume(throwing: error)
                        return
                    }
                    guard let http = response as? HTTPURLResponse else {
                        continuation.resume(
                            throwing: PushError.network("Response was not an HTTP response")
                        )
                        return
                    }
                    continuation.resume(returning: (data ?? Data(), http))
                }
                box.task = task
                task.resume()
            }
        } onCancel: {
            box.task?.cancel()
        }
    }

    func interpret<T>(
        data: Data,
        response: HTTPURLResponse,
        parse: (Data) throws -> T
    ) -> ApiResult<T> {
        let headers = response.allHeaderFields.reduce(into: [String: String]()) { result, entry in
            if let key = entry.key as? String, let value = entry.value as? String {
                result[key] = value
            }
        }

        guard (200...299).contains(response.statusCode) else {
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            let errorObject = (json??["error"] as? [String: Any]) ?? (json ?? nil)
            return .failure(
                ApiError(
                    statusCode: response.statusCode,
                    code: errorObject?["code"] as? String,
                    message: errorObject?["message"] as? String,
                    details: String(data: data.prefix(Self.maxLoggedErrorBody), encoding: .utf8),
                    retryAfter: HTTPHeaderParsing.parseRetryAfter(
                        response.value(forHTTPHeaderField: "Retry-After")
                    )
                )
            )
        }

        do {
            return .success(try parse(data), statusCode: response.statusCode, headers: headers)
        } catch {
            // A 2xx the SDK cannot understand is a contract violation, not a transient fault:
            // reported as a failure so the queue drops it instead of retrying forever.
            return .failure(
                ApiError(
                    statusCode: response.statusCode,
                    code: "invalid_response_body",
                    message: "Could not parse a \(response.statusCode) response: \(error)"
                )
            )
        }
    }
}

/// Holds the in-flight task so the cancellation handler can reach it.
private final class TaskBox: @unchecked Sendable {
    var task: URLSessionDataTask?
}

// MARK: - Request construction

private extension URLSessionRestClient {

    static var jsonContentType: String { "application/json; charset=utf-8" }
    static var platform: String { "ios" }
    static var maxLoggedErrorBody: Int { 512 }

    func buildURL(path: String, query: [String: Any?]) -> URL? {
        let trimmedPath = path.hasPrefix("/") ? String(path.dropFirst()) : path
        let absolute = "\(backendConfig.normalizedBaseURL)/\(backendConfig.apiVersion)/\(trimmedPath)"
        guard var components = URLComponents(string: absolute) else { return nil }

        let items = query.compactMap { key, value -> URLQueryItem? in
            guard let value else { return nil }
            return URLQueryItem(name: key, value: String(describing: value))
        }
        if !items.isEmpty { components.queryItems = items }
        return components.url
    }

    func currentAccessToken() async -> String? {
        guard let authProvider else { return nil }
        return await authProvider.accessToken()
    }

    func buildHeaders(
        extra: [String: String],
        requestId: String,
        token: String?
    ) -> [String: String] {
        let names = backendConfig.headerNames
        var headers: [String: String] = [
            "Accept": "application/json",
            names.sdkVersion: device.sdkVersion,
            names.platform: Self.platform,
            names.requestId: requestId
        ]
        if let appVersion = device.appVersion { headers[names.appVersion] = appVersion }
        if let applicationId = backendConfig.applicationId {
            headers[names.applicationId] = applicationId
        }
        if let installationId = installationIdProvider() {
            headers[names.installationId] = installationId
        }

        headers.merge(backendConfig.defaultHeaders) { _, new in new }
        headers.merge(extra) { _, new in new }

        if let token, !token.isEmpty {
            let scheme = authProvider?.scheme ?? "Bearer"
            headers[names.authorization] = "\(scheme) \(token)"
        }
        return headers
    }

    func encodeBody(_ body: Any?, method: String) -> Data? {
        let requiresBody = method == "POST" || method == "PUT" || method == "PATCH"
        guard let body else {
            // URLSession is happy without a body, but the API expects JSON on these verbs, and
            // an empty object is the least surprising thing to send.
            return requiresBody ? Data("{}".utf8) : nil
        }
        if let data = body as? Data { return data }
        if let string = body as? String { return Data(string.utf8) }
        return try? JSONSerialization.data(
            withJSONObject: JSONSanitizer.sanitize(body),
            options: []
        )
    }

    func logOutcome<T>(
        method: String,
        url: URL,
        requestId: String,
        attempts: Int,
        result: ApiResult<T>
    ) {
        switch result {
        case .success(_, let status, _):
            PushLogger.info("\(method) \(url.path) -> \(status) (requestId=\(requestId))")
        case .failure(let error):
            PushLogger.warn(
                "\(method) \(url.path) -> \(error.statusCode.map(String.init) ?? "?") "
                    + "\(error.code ?? "") after \(attempts) attempt(s) (requestId=\(requestId))"
            )
        case .networkError(let error):
            PushLogger.warn(
                "\(method) \(url.path) failed after \(attempts) attempt(s): \(error) "
                    + "(requestId=\(requestId))"
            )
        }
    }
}

/// Makes an arbitrary dictionary safe for `JSONSerialization`.
///
/// `JSONSerialization` throws on values it does not recognise, and the SDK builds request bodies
/// from dictionaries assembled across several managers. Coercing unknown values to their string
/// description means a new field can never turn into a crash inside a background sync.
enum JSONSanitizer {

    static func sanitize(_ value: Any) -> Any {
        switch value {
        case let dictionary as [String: Any]:
            return dictionary.reduce(into: [String: Any]()) { result, entry in
                result[entry.key] = sanitize(entry.value)
            }
        case let array as [Any]:
            return array.map(sanitize)
        case is NSNull, is String, is NSNumber, is Bool, is Int, is Double:
            return value
        default:
            return String(describing: value)
        }
    }
}
