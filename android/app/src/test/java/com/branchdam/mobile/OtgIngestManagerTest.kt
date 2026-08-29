package com.branchdam.mobile

import com.branchdam.mobile.otg.OtgIngestManager
import com.branchdam.mobile.otg.OtgMediaCandidate
import com.branchdam.mobile.otg.OtgScanResult
import com.branchdam.mobile.otg.OtgState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class OtgIngestManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Test
    fun testCardDetectionTransitionsToAwaitingConfirmation() = runTest(testDispatcher) {
        val root = tempFolder.newFolder("SD_CARD")
        val dcim = File(root, "DCIM/100EOSR5").apply { mkdirs() }
        File(dcim, "IMG_0001.CR3").writeBytes(ByteArray(1024))

        val manager = OtgIngestManager(
            scope = this,
            ioDispatcher = testDispatcher
        )

        assertEquals(OtgState.Idle, manager.state.value)

        manager.onCardDetected("CANON R5", root)

        val state = manager.state.value
        assertTrue(state is OtgState.AwaitingConfirmation)
        val scanResult = (state as OtgState.AwaitingConfirmation).scanResult
        assertEquals("CANON R5", scanResult.deviceLabel)
        assertEquals(1, scanResult.totalCount)
    }

    @Test
    fun testHumanRejectionResetsToIdle() = runTest(testDispatcher) {
        val root = tempFolder.newFolder("SD_CARD")
        val dcim = File(root, "DCIM").apply { mkdirs() }
        File(dcim, "IMG_0001.JPG").writeBytes(ByteArray(512))

        val manager = OtgIngestManager(
            scope = this,
            ioDispatcher = testDispatcher
        )

        manager.onCardDetected("SONY A7IV", root)
        assertTrue(manager.state.value is OtgState.AwaitingConfirmation)

        // Human clicks "Skip / Cancel"
        manager.cancelImport()
        assertEquals(OtgState.Idle, manager.state.value)
    }

    @Test
    fun testHumanConfirmationExecutesImport() = runTest(testDispatcher) {
        val root = tempFolder.newFolder("SD_CARD")
        val dcim = File(root, "DCIM").apply { mkdirs() }
        val sourceFile = File(dcim, "IMG_0001.CR3").apply { writeBytes(ByteArray(2048)) }

        val stageDir = tempFolder.newFolder("otg_stage")

        val manager = OtgIngestManager(
            scope = this,
            ioDispatcher = testDispatcher
        )

        val candidate = OtgMediaCandidate(
            uri = sourceFile.absolutePath,
            relativePath = "DCIM/IMG_0001.CR3",
            fileName = "IMG_0001.CR3",
            sizeBytes = 2048,
            lastModifiedUnix = 1700000000,
            isRaw = true,
            isVideo = false
        )
        val scanResult = OtgScanResult("CANON R5", root.absolutePath, listOf(candidate))

        var stagedCount = 0
        manager.confirmImport(
            scanResult = scanResult,
            destinationDir = stageDir,
            onFileStaged = { _, _ -> stagedCount++ }
        )

        val state = manager.state.value
        assertTrue(state is OtgState.Completed)
        val completed = state as OtgState.Completed
        assertEquals(1, completed.importedCount)
        assertEquals(2048L, completed.totalBytes)
        assertEquals(1, stagedCount)

        val destinationFile = File(stageDir, "IMG_0001.CR3")
        assertTrue(destinationFile.exists())
        assertEquals(2048L, destinationFile.length())
    }
}
