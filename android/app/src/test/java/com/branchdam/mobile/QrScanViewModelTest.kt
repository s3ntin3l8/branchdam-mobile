package com.branchdam.mobile

import com.branchdam.mobile.ui.PairingConfig
import com.branchdam.mobile.ui.qrscan.QrScanViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [QrScanViewModel] — the state machine that drives
 * the QR pairing confirmation flow. The ViewModel is decoupled from
 * CameraX/Compose so it can be tested on the JVM.
 */
class QrScanViewModelTest {

    private fun viewModel() = QrScanViewModel()

    @Test
    fun testInitialStateIsNull() {
        val vm = viewModel()
        assertNull(vm.parsedConfig.value)
        assertFalse(vm.showConfirm.value)
    }

    @Test
    fun testOnQrCodeScanned_validPayload_setsParsedConfigAndShowsConfirm() {
        val vm = viewModel()
        vm.onQrCodeScanned(
            "branchdam://server=http://192.168.1.100:8080&key=abc123&agent=pixel-10"
        )
        val config = vm.parsedConfig.value
        assertEquals("http://192.168.1.100:8080", config?.serverUrl)
        assertEquals("abc123", config?.apiKey)
        assertEquals("pixel-10", config?.agentId)
        assertTrue(vm.showConfirm.value)
    }

    @Test
    fun testOnQrCodeScanned_invalidPayload_doesNotShowConfirm() {
        val vm = viewModel()
        vm.onQrCodeScanned("not a branchdam qr code")
        assertNull(vm.parsedConfig.value)
        assertFalse(vm.showConfirm.value)
    }

    @Test
    fun testOnConfirm_setsApplyResult() {
        val vm = viewModel()
        vm.onQrCodeScanned(
            "branchdam://server=http://10.0.2.2:8080&key=secret&agent=phone"
        )
        vm.onConfirm()
        assertTrue(vm.applyResult.value is QrScanViewModel.ApplyResult.Applied)
        assertFalse(vm.showConfirm.value)
        val result = vm.applyResult.value as QrScanViewModel.ApplyResult.Applied
        assertEquals("http://10.0.2.2:8080", result.config.serverUrl)
        assertEquals("secret", result.config.apiKey)
        assertEquals("phone", result.config.agentId)
    }

    @Test
    fun testOnDismiss_hidesConfirmWithoutApplying() {
        val vm = viewModel()
        vm.onQrCodeScanned(
            "branchdam://server=http://x:8080&key=k&agent=a"
        )
        vm.onDismiss()
        assertFalse(vm.showConfirm.value)
        assertNull(vm.applyResult.value)
    }

    @Test
    fun testOnDismissAfterConfirm_resetsForNextScan() {
        val vm = viewModel()
        vm.onQrCodeScanned(
            "branchdam://server=http://x:8080&key=k&agent=a"
        )
        vm.onConfirm()
        assertTrue(vm.applyResult.value is QrScanViewModel.ApplyResult.Applied)

        // Second scan should work
        vm.onQrCodeScanned(
            "branchdam://server=http://y:8080&key=j&agent=b"
        )
        val config = vm.parsedConfig.value
        assertEquals("http://y:8080", config?.serverUrl)
        assertTrue(vm.showConfirm.value)
    }

    @Test
    fun testScanGateInitialStateIsFalse() {
        val vm = viewModel()
        assertFalse(vm.scanGate.value)
    }

    @Test
    fun testTryConsumeScanGate_isAtomicAndOneShot() {
        // Camera analyzer calls tryConsumeScanGate() on every successful
        // frame. Only the first call should win — subsequent frames must
        // return false until resetScan() re-arms the gate.
        val vm = viewModel()
        assertTrue(vm.tryConsumeScanGate())
        assertFalse(vm.tryConsumeScanGate())
        assertFalse(vm.tryConsumeScanGate())
        assertTrue(vm.scanGate.value)
    }

    @Test
    fun testResetScan_rearmsScanGate() {
        val vm = viewModel()
        vm.tryConsumeScanGate()
        vm.resetScan()
        assertFalse(vm.scanGate.value)
        assertTrue(vm.tryConsumeScanGate())
    }

    @Test
    fun testOnDismiss_resetsScanGate() {
        val vm = viewModel()
        vm.tryConsumeScanGate()
        vm.onQrCodeScanned(
            "branchdam://server=http://x:8080&key=k&agent=a"
        )
        vm.onDismiss()
        assertFalse(vm.scanGate.value)
        assertTrue(vm.tryConsumeScanGate())
    }

    @Test
    fun testConsumeApplyResult_resetsScanGate() {
        val vm = viewModel()
        vm.onQrCodeScanned(
            "branchdam://server=http://x:8080&key=k&agent=a"
        )
        vm.tryConsumeScanGate()
        vm.onConfirm()
        vm.consumeApplyResult()
        assertFalse(vm.scanGate.value)
    }
}
