package com.branchdam.mobile.ui

data class PairingConfig(
    val serverUrl: String,
    val apiKey: String,
    val agentId: String,
)

object QrParser {
    fun parseQrPayload(payload: String): PairingConfig? {
        if (!payload.startsWith("branchdam://")) return null
        val clean = payload.removePrefix("branchdam://")
        val params = clean.split("&").associate {
            val parts = it.split("=")
            if (parts.size == 2) parts[0] to parts[1] else "" to ""
        }

        val server = params["server"] ?: return null
        val key = params["key"] ?: ""
        val agent = params["agent"] ?: "pixel-fold"
        return PairingConfig(serverUrl = server, apiKey = key, agentId = agent)
    }
}
