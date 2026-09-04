package com.branchdam.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class AuditCandidate(
    val edgeId: String,
    val masterFilename: String,
    val childFilename: String,
    val confidence: Double,
    val resolver: String,
)

@Composable
fun AuditQueueScreen(
    candidates: List<AuditCandidate>,
    onConfirm: (AuditCandidate) -> Unit,
    onReject: (AuditCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = candidates.firstOrNull()

    if (current == null) {
        Box(
            modifier = modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("All edge lineage candidates reviewed", style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "Lineage Audit Queue (${candidates.size} pending)",
            style = MaterialTheme.typography.titleLarge,
        )

        Card(
            modifier = Modifier.fillMaxWidth().height(260.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxSize(),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Text("Master Asset: ${current.masterFilename}", style = MaterialTheme.typography.bodyLarge)
                Text("Derivative: ${current.childFilename}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Match Confidence: ${(current.confidence * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Resolver: ${current.resolver}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Button(
                onClick = { onReject(current) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373)),
            ) {
                Text("Reject Edge")
            }

            Button(
                onClick = { onConfirm(current) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81C784)),
            ) {
                Text("Confirm Edge")
            }
        }
    }
}
