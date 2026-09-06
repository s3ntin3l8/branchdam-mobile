package com.branchdam.mobile.ui

import java.net.URLDecoder

data class PairingConfig(
    val serverUrl: String,
    val apiKey: String,
    val agentId: String,
)

object QrParser {
    /**
     * Accepts both the form documented in the README
     * (`branchdam://?server=…&key=…&agent=…`, with a literal `?`
     * separator after the scheme) and the shorter form
     * (`branchdam://server=…&key=…&agent=…`, no `?`).
     *
     * The server-side `qrPayloadFor()` emits values via
     * `url.Values.Encode()`; each segment value is percent-decoded
     * here before use. Malformed percent-encoding (stray `%` not
     * followed by two hex digits) is rejected by returning `null`
     * rather than silently passing the raw string downstream.
     */
    fun parseQrPayload(payload: String): PairingConfig? {
        if (!payload.startsWith("branchdam://")) return null
        val body = payload
            .removePrefix("branchdam://")
            .let { if (it.startsWith("?")) it.removePrefix("?") else it }

        val params = mutableMapOf<String, String>()
        for (segment in body.split("&")) {
            if (segment.isEmpty()) continue
            val parts = segment.split("=", limit = 2)
            if (parts.size != 2) return null
            val decoded = try {
                URLDecoder.decode(parts[1], "UTF-8")
            } catch (_: IllegalArgumentException) {
                return null
            }
            params[parts[0]] = decoded
        }

        val server = params["server"]?.takeIf { it.isNotBlank() } ?: return null
        val key = params["key"].orEmpty()
        val agent = params["agent"]?.takeIf { it.isNotBlank() } ?: "pixel-fold"
        return PairingConfig(serverUrl = server, apiKey = key, agentId = agent)
    }
}
