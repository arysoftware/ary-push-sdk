import Foundation

/// Transport contract used by every SDK component that talks to the push API.
///
/// Deliberately free of push semantics: it knows about HTTP verbs, JSON, headers, timeouts and
/// retries, and nothing about installations, tokens or tags. Push logic lives in ``PushBackend``
/// implementations, which keeps it testable without a socket.
///
/// Paths are relative to the configured base URL and API version, e.g. `installations`.
public protocol RestClient: AnyObject {

    func get<T>(
        path: String,
        query: [String: Any?],
        headers: [String: String],
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T>

    func post<T>(
        path: String,
        body: Any?,
        headers: [String: String],
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T>

    func put<T>(
        path: String,
        body: Any?,
        headers: [String: String],
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T>

    func patch<T>(
        path: String,
        body: Any?,
        headers: [String: String],
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T>

    func delete(
        path: String,
        query: [String: Any?],
        headers: [String: String]
    ) async -> ApiResult<Void>

    /// Releases transport resources.
    func close()
}

/// Parser for endpoints whose response body is irrelevant.
public let ignoreBody: (Data) throws -> Void = { _ in }

public extension RestClient {

    func get<T>(path: String, parse: @escaping (Data) throws -> T) async -> ApiResult<T> {
        await get(path: path, query: [:], headers: [:], parse: parse)
    }

    func post<T>(
        path: String,
        body: Any?,
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T> {
        await post(path: path, body: body, headers: [:], parse: parse)
    }

    func put<T>(
        path: String,
        body: Any?,
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T> {
        await put(path: path, body: body, headers: [:], parse: parse)
    }

    func patch<T>(
        path: String,
        body: Any?,
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T> {
        await patch(path: path, body: body, headers: [:], parse: parse)
    }

    func delete(path: String, query: [String: Any?] = [:]) async -> ApiResult<Void> {
        await delete(path: path, query: query, headers: [:])
    }
}
