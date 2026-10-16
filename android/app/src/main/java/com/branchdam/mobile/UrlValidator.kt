package com.branchdam.mobile

import java.net.URI

/**
 * URL validation for server addresses. Release builds require HTTPS for
 * every host, LAN addresses included. Debug builds also allow cleartext
 * HTTP to exactly the [DEV_CLEARTEXT_HOSTS]; the debug network security
 * config (src/debug/res/xml/network_security_config.xml) lists the same
 * hosts, and NetworkSecurityConfigTest keeps the two in step.
 */
object UrlValidator {

    val DEV_CLEARTEXT_HOSTS: List<String> = listOf(
        "10.0.2.2",
        "localhost",
        "127.0.0.1",
        "192.168.1.100",
    )

    /**
     * Comma-separated form of [DEV_CLEARTEXT_HOSTS] suitable for
     * the `devCleartextHosts` argument of [com.branchdam.mobile.EngineHolder.initialize].
     * Returns an empty string when [isDebug] is false so the engine
     * rejects cleartext traffic in release.
     */
    fun cleartextHostsCsv(isDebug: Boolean): String =
        if (isDebug) DEV_CLEARTEXT_HOSTS.joinToString(",") else ""

    /**
     * Returns true if [url] is a valid HTTP(S) URL whose host is
     * permitted for the given build type. In debug builds the
     * [DEV_CLEARTEXT_HOSTS] allowlist is checked for HTTP URLs.
     * In release builds only HTTPS is accepted.
     *
     * Rejects javascript:, file:, data:, and other non-network schemes.
     */
    fun isValidServerUrl(url: String, isDebug: Boolean): Boolean {
        if (url.isBlank()) return false
        val uri = try {
            URI.create(url)
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        if (uri.host.isNullOrBlank()) return false
        if (scheme == "http") {
            if (!isDebug) return false
            val host = uri.host!!.lowercase()
            if (host !in DEV_CLEARTEXT_HOSTS) return false
        }
        return true
    }
}
