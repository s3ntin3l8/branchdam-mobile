package com.branchdam.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class PairingConfig(
    val serverUrl: String,
    val apiKey: String,
    val agentId: String
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

@Composable
fun QrPairingScreen(
    onPairingComplete: (PairingConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var manualUrl by remember { mutableStateOf("http://192.168.1.100:8080") }
    var manualKey by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("branchDAM Server Pairing", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = manualUrl,
            onValueChange = { manualUrl = it },
            label = { Text("Server URL") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = manualKey,
            onValueChange = { manualKey = it },
            label = { Text("API Key (Optional)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                onPairingComplete(PairingConfig(manualUrl, manualKey, "pixel-10-fold"))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save and Connect")
        }
    }
}
