package com.branchdam.mobile.ui.qrscan

import androidx.lifecycle.ViewModel
import com.branchdam.mobile.ui.PairingConfig
import com.branchdam.mobile.ui.QrParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the QR pairing confirmation flow. Decoupled from CameraX
 * and Compose so it can be unit-tested on the JVM.
 *
 * Flow: scan → [onQrCodeScanned] → show confirm dialog →
 * [onConfirm] (returns [ApplyResult.Applied]) → caller saves config.
 */
class QrScanViewModel : ViewModel() {

    sealed interface ApplyResult {
        data class Applied(val config: PairingConfig) : ApplyResult
    }

    private val _parsedConfig = MutableStateFlow<PairingConfig?>(null)
    val parsedConfig: StateFlow<PairingConfig?> = _parsedConfig.asStateFlow()

    private val _showConfirm = MutableStateFlow(false)
    val showConfirm: StateFlow<Boolean> = _showConfirm.asStateFlow()

    private val _applyResult = MutableStateFlow<ApplyResult?>(null)
    val applyResult: StateFlow<ApplyResult?> = _applyResult.asStateFlow()

    private val _applyError = MutableStateFlow<String?>(null)
    val applyError: StateFlow<String?> = _applyError.asStateFlow()

    fun onQrCodeScanned(payload: String) {
        val config = QrParser.parseQrPayload(payload) ?: return
        _parsedConfig.value = config
        _showConfirm.value = true
        _applyResult.value = null
        _applyError.value = null
    }

    fun onConfirm() {
        val config = _parsedConfig.value ?: return
        _showConfirm.value = false
        _applyResult.value = ApplyResult.Applied(config)
    }

    fun onDismiss() {
        _showConfirm.value = false
        _parsedConfig.value = null
        _applyResult.value = null
        _applyError.value = null
    }

    fun consumeApplyResult() {
        _applyResult.value = null
    }
}
