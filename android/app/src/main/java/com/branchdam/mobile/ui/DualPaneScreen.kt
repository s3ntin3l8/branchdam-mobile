package com.branchdam.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DualPaneScreen(
    posture: DevicePosture,
    modifier: Modifier = Modifier
) {
    when (posture) {
        is DevicePosture.Flat -> {
            AuditQueueScreen(
                candidates = listOf(
                    AuditCandidate("1", "PXL_20260829_001.dng", "PXL_20260829_001.jpg", 1.00, "android_camera_pair")
                ),
                onConfirm = {},
                onReject = {},
                modifier = modifier
            )
        }
        is DevicePosture.Tabletop -> {
            Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Text("Lineage Canvas", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {}) {
                        Text("Free Up Space (Verified Masters)")
                    }
                }

                Divider(modifier = Modifier.fillMaxWidth().height(1.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    AuditQueueScreen(
                        candidates = listOf(
                            AuditCandidate("1", "PXL_20260829_001.dng", "PXL_20260829_001.jpg", 1.00, "android_camera_pair")
                        ),
                        onConfirm = {},
                        onReject = {}
                    )
                }
            }
        }
        is DevicePosture.Book -> {
            Row(modifier = modifier.fillMaxSize().padding(16.dp)) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(end = 8.dp)
                ) {
                    Text("Master Library & Ingest", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {}) {
                        Text("Free Up Space (Verified Masters)")
                    }
                }

                Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 8.dp)
                ) {
                    AuditQueueScreen(
                        candidates = listOf(
                            AuditCandidate("1", "PXL_20260829_001.dng", "PXL_20260829_001.jpg", 1.00, "android_camera_pair")
                        ),
                        onConfirm = {},
                        onReject = {}
                    )
                }
            }
        }
    }
}
