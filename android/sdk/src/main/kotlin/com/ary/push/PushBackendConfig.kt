package com.ary.push

/**
 * Points the SDK at one of ARY's push API environments.
 *
 * Environment selection belongs to the consuming application: the SDK never hardcodes a URL and
 * never needs rebuilding for development, QA, staging or production.
 *
 * Omit this entirely and the SDK runs against [com.ary.push.backend.NoopPushBackend], which
 * keeps every push feature working with no server at all.
 */
public data class PushBackendConfig @JvmOverloads constructor(
    /** Base URL of the push API, e.g. `https://push-api.ary.com`. Must be HTTPS in production. */
    public val baseUrl: String,

    /**
     * Optional PUBLIC application identifier, e.g. `wallet_android`.
     *
     * This distinguishes applications on the backend. It is not a credential, it is not secret,
     * and the backend must never treat it as authentication.
     */
    public val applicationId: String? = null,

    /** API version prefix. Endpoints are always versioned. */
    public val apiVersion: String = "v1",

    /** Extra static headers merged into every SDK request. Never put secrets here. */
    public val defaultHeaders: Map<String, String> = emptyMap(),

    /** Header names, overridable for gateways that expect a different convention. */
    public val headerNames: HeaderNames = HeaderNames()
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "baseUrl must be an absolute http(s) URL"
        }
        require(apiVersion.isNotBlank()) { "apiVersion must not be blank" }
    }

    /** Normalised base URL without a trailing slash. */
    internal val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')

    /** True when the endpoint is plaintext HTTP, which is only ever acceptable for local testing. */
    internal val isPlaintext: Boolean get() = baseUrl.startsWith("http://")

    /** Configurable request header names. */
    public data class HeaderNames @JvmOverloads constructor(
        public val sdkVersion: String = "X-SDK-Version",
        public val platform: String = "X-Platform",
        public val appVersion: String = "X-App-Version",
        public val requestId: String = "X-Request-ID",
        public val applicationId: String = "X-Application-Id",
        public val installationId: String = "X-Installation-Id",
        public val authorization: String = "Authorization"
    )
}
