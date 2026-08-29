package com.branchdam.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.branchdam.mobile.R

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
fun BrandMonogram(
    size: Dp = 64.dp,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(id = R.drawable.ic_brand_monogram),
        contentDescription = "branchDAM Monogram",
        modifier = modifier.size(size)
    )
}

@Composable
fun QrPairingScreen(
    onPairingComplete: (PairingConfig) -> Unit,
    onFetchNamingTemplate: (() -> String)? = null,
    initialNamingTemplate: String = "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}",
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var manualUrl by remember { mutableStateOf("http://192.168.1.100:8080") }
    var manualKey by remember { mutableStateOf("") }
    var namingTemplate by remember { mutableStateOf(initialNamingTemplate) }
    var syncOnMobileData by remember { mutableStateOf(com.branchdam.mobile.service.SyncScheduler.getSyncOnMobileData(context)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BrandMonogram(size = 64.dp)
        Spacer(Modifier.height(16.dp))
        Text("branchDAM Server Settings", style = MaterialTheme.typography.headlineSmall)
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

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = namingTemplate,
            onValueChange = {},
            readOnly = true,
            label = { Text("Server Naming Template") },
            supportingText = { Text("Synchronized via server handshake (POST /api/v1/agent/upload)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sync on Mobile Data", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Allow immediate sync over cellular/metered networks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = syncOnMobileData,
                onCheckedChange = {
                    syncOnMobileData = it
                    com.branchdam.mobile.service.SyncScheduler.setSyncOnMobileData(context, it)
                }
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                onPairingComplete(PairingConfig(manualUrl, manualKey, "pixel-10-fold"))
                if (onFetchNamingTemplate != null) {
                    val fetched = onFetchNamingTemplate()
                    if (fetched.isNotBlank()) {
                        namingTemplate = fetched
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save and Connect")
        }
    }
}
