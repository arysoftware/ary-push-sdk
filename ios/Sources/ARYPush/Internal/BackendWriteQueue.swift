import Foundation

/// Orders acknowledged preference changes with durable sync writes across suspension points.
/// An actor alone is insufficient: another call may enter it while an HTTP request is awaited.
actor BackendWriteQueue {
    private var tail: Task<Void, Never>?

    func run<T>(_ operation: @escaping () async -> T) async -> T {
        let previous = tail
        let task = Task {
            await previous?.value
            return await operation()
        }
        tail = Task { _ = await task.value }
        return await task.value
    }
}
