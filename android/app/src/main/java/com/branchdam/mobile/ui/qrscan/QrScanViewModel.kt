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
 * Flow: scan → [tryConsumeScanGate] wins → [onQrCodeScanned] → show
 * confirm dialog → [onConfirm] (returns [ApplyResult.Applied]) →
 * caller saves config → [consumeApplyResult] / [onDismiss] re-arms
 * the scan gate so a second QR can be scanned.
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

    private val _scanGate = MutableStateFlow(false)
    val scanGate: StateFlow<Boolean> = _scanGate.asStateFlow()

    /**
     * Atomic compare-and-set on [scanGate]. The camera analyzer
     * calls this on every successful frame and only proceeds with
     * the first one — subsequent frames (which can arrive from the
     * image-analysis STRATEGY_KEEP_ONLY_LATEST queue while the
     * confirm dialog is up) lose the race and become no-ops.
     *
     * Returns true if this caller flipped the gate from false to
     * true (i.e. should fire the on-barcode-scanned callback).
     */
    fun tryConsumeScanGate(): Boolean =
        _scanGate.compareAndSet(expect = false, update = true)

    fun resetScan() {
        _scanGate.value = false
    }

    fun onQrCodeScanned(payload: String) {
        val config = QrParser.parseQrPayload(payload) ?: return
        _parsedConfig.value = config
        _showConfirm.value = true
        _applyResult.value = null
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
        resetScan()
    }

    fun consumeApplyResult() {
        _applyResult.value = null
        resetScan()
    }
}
