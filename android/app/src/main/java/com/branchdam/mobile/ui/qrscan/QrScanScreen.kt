package com.branchdam.mobile.ui.qrscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.branchdam.mobile.ui.PairingConfig
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

private const val TAG = "QrScanScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    onNavigateBack: () -> Unit,
    onConfigApplied: (PairingConfig) -> Unit,
    viewModel: QrScanViewModel = viewModel(),
) {
    val context = LocalContext.current
    val parsedConfig by viewModel.parsedConfig.collectAsStateWithLifecycle()
    val showConfirm by viewModel.showConfirm.collectAsStateWithLifecycle()
    val applyResult by viewModel.applyResult.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(applyResult) {
        if (applyResult is QrScanViewModel.ApplyResult.Applied) {
            val config = (applyResult as QrScanViewModel.ApplyResult.Applied).config
            viewModel.consumeApplyResult()
            onConfigApplied(config)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan QR Code") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (hasCameraPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                CameraPreviewWithAnalysis(
                    onBarcodeScanned = { viewModel.onQrCodeScanned(it) },
                    isScanGated = { viewModel.tryConsumeScanGate() },
                    modifier = Modifier.fillMaxSize(),
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(250.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .border(2.dp, Color.White, RoundedCornerShape(16.dp)),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Point camera at QR code",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        } else {
            PermissionDeniedPanel(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            )
        }
    }

    if (showConfirm && parsedConfig != null) {
        PairingConfirmDialog(
            config = parsedConfig!!,
            onConfirm = { viewModel.onConfirm() },
            onDismiss = { viewModel.onDismiss() },
        )
    }
}

@Composable
private fun PermissionDeniedPanel(
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val permanentlyDenied = activity != null &&
        !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (permanentlyDenied) {
                "Camera permission was permanently denied. Open Settings to grant it."
            } else {
                "Camera permission is required to scan QR codes."
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        if (permanentlyDenied) {
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
            ) {
                Text("Open Settings")
            }
        } else {
            OutlinedButton(onClick = onRequest) {
                Text("Grant Permission")
            }
        }
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun PairingConfirmDialog(
    config: PairingConfig,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with Server", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Server URL", config.serverUrl)
                DetailRow(
                    "API Key",
                    config.apiKey.ifBlank { "(none)" }.let { maskApiKey(it) },
                )
                DetailRow("Agent ID", config.agentId)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Save and Connect")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Mask an API key for display: keep the first 4 and last 2
 * characters visible if long enough, otherwise show all bullets.
 * Empty input is returned unchanged so callers can substitute
 * `(none)` before masking.
 */
private fun maskApiKey(value: String): String {
    if (value.isBlank() || value == "(none)") return value
    if (value.length <= 6) return "•".repeat(value.length)
    val head = value.take(4)
    val tail = value.takeLast(2)
    return "$head${"•".repeat((value.length - 6).coerceAtLeast(4))}$tail"
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CameraPreviewWithAnalysis(
    onBarcodeScanned: (String) -> Unit,
    isScanGated: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    // Captured so onDispose can unbindAll() — without this the
    // camera stays bound to the lifecycle after navigation,
    // which leaks the PreviewView and blocks subsequent binds.
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            cameraProviderRef.value?.unbindAll()
            cameraExecutor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                cameraProviderRef.value = cameraProvider

                // Defer the bind until the lifecycle is at least STARTED
                // — bindToLifecycle requires it and would throw otherwise.
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { imageAnalysis ->
                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(
                                imageProxy = imageProxy,
                                scanner = scanner,
                                onFound = { barcode ->
                                    val raw = barcode.rawValue
                                    if (raw != null && isScanGated()) {
                                        onBarcodeScanned(raw)
                                    }
                                },
                            )
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = modifier,
    )
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImageProxy(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onFound: (Barcode) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull()?.let(onFound)
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "Barcode scan failed", e)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}
