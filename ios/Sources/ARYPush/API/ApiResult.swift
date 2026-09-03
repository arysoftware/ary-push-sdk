import Foundation

/// Outcome of a single REST call.
///
/// Transport failures are modelled separately from server-reported failures because only the
/// former, plus a specific set of status codes, may be retried.
public enum ApiResult<T> {

    /// A 2xx response whose body decoded successfully.
    case success(T, statusCode: Int, headers: [String: String] = [:])

    /// A non-2xx response, or a 2xx response whose body could not be decoded.
    case failure(ApiError)

    /// The request never produced a response: DNS, TLS, timeout, connection reset, cancellation.
    case networkError(Error)

    public var isSuccess: Bool {
        if case .success = self { return true }
        return false
    }

    public var value: T? {
        if case .success(let value, _, _) = self { return value }
        return nil
    }

    /// Whether re-sending this exact request could plausibly succeed.
    ///
    /// Transient by definition: 408, 425, 429 and 5xx, plus every transport failure. Permanent
    /// client errors (400, 401, 403, 404, 409, 422) are not retried here; 401 has its own
    /// single-shot refresh path in the REST client.
    public var isRetryable: Bool {
        switch self {
        case .success:
            return false
        case .networkError:
            return true
        case .failure(let error):
            guard let status = error.statusCode else { return true }
            if status == 408 || status == 425 || status == 429 { return true }
            return (500...599).contains(status)
        }
    }

    public func map<R>(_ transform: (T) -> R) -> ApiResult<R> {
        switch self {
        case .success(let value, let status, let headers):
            return .success(transform(value), statusCode: status, headers: headers)
        case .failure(let error):
            return .failure(error)
        case .networkError(let error):
            return .networkError(error)
        }
    }
}

/// A failure the server reported.
public struct ApiError: Error, Equatable {
    public let statusCode: Int?
    public let code: String?
    public let message: String?
    public let details: String?

    /// Server-supplied `Retry-After`, when present and parseable.
    public let retryAfter: TimeInterval?

    public init(
        statusCode: Int?,
        code: String? = nil,
        message: String? = nil,
        details: String? = nil,
        retryAfter: TimeInterval? = nil
    ) {
        self.statusCode = statusCode
        self.code = code
        self.message = message
        self.details = details
        self.retryAfter = retryAfter
    }
}

/// Structured SDK errors.
///
/// Reported through logs and callbacks. The SDK never throws these across a public API boundary
/// as a crash: a push or network failure must not take the host application down with it.
public enum PushError: Error {
    case initialization(String, underlying: Error? = nil)
    case permission(String, underlying: Error? = nil)
    case token(String, underlying: Error? = nil)
    case notification(String, underlying: Error? = nil)
    case storage(String, underlying: Error? = nil)
    case network(String, underlying: Error? = nil)
    case backend(String, statusCode: Int? = nil, code: String? = nil)

    public var message: String {
        switch self {
        case .initialization(let m, _), .permission(let m, _), .token(let m, _),
             .notification(let m, _), .storage(let m, _), .network(let m, _):
            return m
        case .backend(let m, _, _):
            return m
        }
    }
}
