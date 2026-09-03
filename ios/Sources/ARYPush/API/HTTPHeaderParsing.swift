import Foundation

/// Parsing helpers shared by REST client implementations.
enum HTTPHeaderParsing {

    private static let maxSaneRetryAfter: TimeInterval = 24 * 60 * 60

    private static let httpDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return formatter
    }()

    /// Parses `Retry-After`, which RFC 9110 allows to be either delta-seconds or an HTTP date.
    ///
    /// Returns `nil` for absent or unparseable values, and for values far enough in the future to
    /// be a server bug rather than an instruction.
    static func parseRetryAfter(_ value: String?, now: Date = Date()) -> TimeInterval? {
        guard let raw = value?.trimmingCharacters(in: .whitespaces), !raw.isEmpty else {
            return nil
        }

        if let seconds = TimeInterval(raw) {
            guard seconds >= 0 else { return nil }
            return min(seconds, maxSaneRetryAfter)
        }

        guard let date = httpDateFormatter.date(from: raw) else { return nil }
        let delta = date.timeIntervalSince(now)
        return delta <= 0 ? 0 : min(delta, maxSaneRetryAfter)
    }

    /// Formats an instant as an HTTP date. Used by tests and by request diagnostics.
    static func formatHTTPDate(_ date: Date) -> String {
        httpDateFormatter.string(from: date)
    }
}
