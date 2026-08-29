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

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

val BrandTeal = Color(0xFF2BA69A)

@Composable
fun BrandMonogram(
    size: androidx.compose.ui.unit.Dp = 64.dp,
    color: Color = BrandTeal,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.width / 64f

        // 1. Stem: rect x=6, y=5, width=9, height=54, rx=4.5
        drawRoundRect(
            color = color,
            topLeft = Offset(6f * scale, 5f * scale),
            size = Size(9f * scale, 54f * scale),
            cornerRadius = CornerRadius(4.5f * scale, 4.5f * scale)
        )

        // 2. Main loop: circle cx=28, cy=40, r=14.5, stroke=7.5
        drawCircle(
            color = color,
            radius = 14.5f * scale,
            center = Offset(28f * scale, 40f * scale),
            style = Stroke(width = 7.5f * scale)
        )

        // 3. Connector line: M43 40 H48, width=6
        drawLine(
            color = color,
            start = Offset(43f * scale, 40f * scale),
            end = Offset(48f * scale, 40f * scale),
            strokeWidth = 6f * scale,
            cap = StrokeCap.Round
        )

        // 4. Child node: circle cx=54, cy=40, r=6
        drawCircle(
            color = color,
            radius = 6f * scale,
            center = Offset(54f * scale, 40f * scale)
        )
    }
}

@Composable
fun QrPairingScreen(
    onPairingComplete: (PairingConfig) -> Unit,
    onFetchNamingTemplate: (() -> String)? = null,
    initialNamingTemplate: String = "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}",
    modifier: Modifier = Modifier
) {
    var manualUrl by remember { mutableStateOf("http://192.168.1.100:8080") }
    var manualKey by remember { mutableStateOf("") }
    var namingTemplate by remember { mutableStateOf(initialNamingTemplate) }

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
