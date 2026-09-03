import Foundation

/// Supplies the host application's access token to the SDK's REST client.
///
/// The SDK never stores, refreshes or owns credentials: it asks the host application for a token
/// when it needs one. Implementations must be safe to call from any task.
///
/// Returning `nil` sends the request unauthenticated.
public protocol AuthProvider: AnyObject {

    /// Returns the current access token, or `nil` when the user is not authenticated.
    func accessToken() async -> String?

    /// Called once when the backend answers `401 Unauthorized`.
    ///
    /// Return `true` if a fresh token is now available, in which case the SDK retries the
    /// request exactly once. The SDK never loops on refresh.
    func refreshAccessToken() async -> Bool

    /// Scheme prefixed to the token in the `Authorization` header.
    var scheme: String { get }
}

public extension AuthProvider {
    func refreshAccessToken() async -> Bool { false }
    var scheme: String { "Bearer" }
}
