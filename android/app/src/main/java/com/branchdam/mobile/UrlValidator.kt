package com.branchdam.mobile

import java.net.URI

/**
 * URL validation for server addresses. Debug builds allow cleartext
 * HTTP to emulator/LAN hosts; production requires HTTPS.
 */
object UrlValidator {

    val DEV_CLEARTEXT_HOSTS: List<String> = listOf(
        "10.0.2.2",
        "localhost",
        "127.0.0.1",
        "192.168.1.100",
    )

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
