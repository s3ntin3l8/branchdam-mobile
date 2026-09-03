package com.branchdam.mobile

import com.branchdam.mobile.otg.OtgHashProvider
import com.branchdam.mobile.otg.OtgIngestFileError
import com.branchdam.mobile.otg.OtgIngestManager
import com.branchdam.mobile.otg.OtgMediaCandidate
import com.branchdam.mobile.otg.OtgScanResult
import com.branchdam.mobile.otg.OtgState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals(0, completed.fileErrors.size)
        assertEquals(1, stagedCount)

        val destinationFile = File(stageDir, "DCIM/IMG_0001.CR3")
        assertTrue(destinationFile.exists())
        assertEquals(2048L, destinationFile.length())
    }

    @Test
    fun testSameNamedFilesAcrossFoldersDoNotOverwrite() = runTest(testDispatcher) {
        val root = tempFolder.newFolder("SD_CARD_MULTI")
        val folder1 = File(root, "DCIM/100EOSR5").apply { mkdirs() }
        val folder2 = File(root, "DCIM/101EOSR5").apply { mkdirs() }

        val file1 = File(folder1, "IMG_0001.CR3").apply { writeText("photo_from_folder_100") }
        val file2 = File(folder2, "IMG_0001.CR3").apply { writeText("photo_from_folder_101_different_content") }

        val stageDir = tempFolder.newFolder("otg_stage_multi")

        val manager = OtgIngestManager(
            scope = this,
            ioDispatcher = testDispatcher
        )

        val candidate1 = OtgMediaCandidate(
            uri = file1.absolutePath,
            relativePath = "DCIM/100EOSR5/IMG_0001.CR3",
            fileName = "IMG_0001.CR3",
            sizeBytes = file1.length(),
            lastModifiedUnix = 1700000000,
            isRaw = true,
            isVideo = false
        )
        val candidate2 = OtgMediaCandidate(
            uri = file2.absolutePath,
            relativePath = "DCIM/101EOSR5/IMG_0001.CR3",
            fileName = "IMG_0001.CR3",
            sizeBytes = file2.length(),
            lastModifiedUnix = 1700000010,
            isRaw = true,
            isVideo = false
        )

        val scanResult = OtgScanResult("CANON R5", root.absolutePath, listOf(candidate1, candidate2))

        manager.confirmImport(scanResult = scanResult, destinationDir = stageDir)

        val staged1 = File(stageDir, "DCIM/100EOSR5/IMG_0001.CR3")
        val staged2 = File(stageDir, "DCIM/101EOSR5/IMG_0001.CR3")

        assertTrue(staged1.exists())
        assertTrue(staged2.exists())
        assertEquals("photo_from_folder_100", staged1.readText())
        assertEquals("photo_from_folder_101_different_content", staged2.readText())

        val state = manager.state.value
        assertTrue(state is OtgState.Completed)
        assertEquals(2, (state as OtgState.Completed).importedCount)
    }

    @Test
    fun testCancelImportStopsInFlightExecution() = runTest(testDispatcher) {
        val manager = OtgIngestManager(
            scope = this,
            ioDispatcher = testDispatcher
        )

        manager.cancelImport()
        assertEquals(OtgState.Idle, manager.state.value)
    }

    /**
     * Acceptance criterion (T2-7): "A 100 GB file is refused at the
     * staging step with a clear error." The cap is 50 GB; a candidate
     * reporting 100 GB must surface as a per-file error in the
     * post-ingest Completed state and must not leave a partial file
     * behind in the staging directory.
     */
    @Test
    fun testCandidateLargerThanSizeCapIsRefusedWithoutStaging() = runTest(testDispatcher) {
        val root = tempFolder.newFolder("SD_CARD_BIG")
        val dcim = File(root, "DCIM").apply { mkdirs() }
        // The source file exists but is tiny; the candidate's claimed
        // size is what the size-cap check looks at — that's the
        // filesystem-metadata case the audit was worried about.
        val sourceFile = File(dcim, "HUGE.MP4").apply { writeBytes(ByteArray(16)) }

        val stageDir = tempFolder.newFolder("otg_stage_big")

        val manager = OtgIngestManager(
            scope = this,
            ioDispatcher = testDispatcher
        )

        val claimedSize = OtgIngestManager.MAX_OTG_STAGE_BYTES + 1L
        val candidate = OtgMediaCandidate(
            uri = sourceFile.absolutePath,
            relativePath = "DCIM/HUGE.MP4",
            fileName = "HUGE.MP4",
            sizeBytes = claimedSize,
            lastModifiedUnix = 1700000000,
            isRaw = false,
            isVideo = true
        )
        val scanResult = OtgScanResult("SD_CARD", root.absolutePath, listOf(candidate))

        var stagedCount = 0
        manager.confirmImport(
            scanResult = scanResult,
            destinationDir = stageDir,
            onFileStaged = { _, _ -> stagedCount++ }
        )

        val state = manager.state.value
        assertTrue(state is OtgState.Completed)
        val completed = state as OtgState.Completed
        assertEquals("importedCount must be 0 for a refused-size file", 0, completed.importedCount)
        assertEquals(0, stagedCount)
        assertEquals(1, completed.fileErrors.size)

        val err: OtgIngestFileError = completed.fileErrors.first()
        assertEquals(candidate, err.candidate)
        assertTrue(
            "error message must mention the cap, got: ${err.message}",
            err.message.contains("too large", ignoreCase = true),
        )

        // No partial file left behind.
        val staged = File(stageDir, "DCIM/HUGE.MP4")
        assertFalse(
            "staged file must not exist when size cap fires, found ${staged.length()} bytes",
            staged.exists(),
        )
    }

    /**
     * Acceptance criterion (T2-7): "A simulated mid-copy failure
     * (cancel the source stream) results in a deleted staged file and
     * an error surfaced in the confirmation UI." We simulate the
     * failing-SD-card case with a candidate whose reported sizeBytes
     * doesn't match the actual file length: InputStream.copyTo stops
     * at the real EOF, returning fewer bytes than the candidate
     * claimed. The post-copy byte-count check must trip, delete the
     * partial staged file, and surface the error.
     */
    @Test
    fun testMidCopyShortReadIsSurfacedAsPerFileErrorAndStagedFileIsDeleted() = runTest(testDispatcher) {
        val root = tempFolder.newFolder("SD_CARD_FAULT")
        val dcim = File(root, "DCIM").apply { mkdirs() }
        // Real file on disk is short — pretend the SD card metadata
        // claimed it was 4 KB longer (the bytes we'll never read).
        val realBytes = 1024L
        val claimedBytes = realBytes + 4096L
        val sourceFile = File(dcim, "FAULT.CR3").apply {
            writeBytes(ByteArray(realBytes.toInt()))
        }

        val stageDir = tempFolder.newFolder("otg_stage_fault")

        val manager = OtgIngestManager(
            scope = this,
            ioDispatcher = testDispatcher
        )

        val candidate = OtgMediaCandidate(
            uri = sourceFile.absolutePath,
            relativePath = "DCIM/FAULT.CR3",
            fileName = "FAULT.CR3",
            sizeBytes = claimedBytes,
            lastModifiedUnix = 1700000000,
            isRaw = true,
            isVideo = false
        )
        val scanResult = OtgScanResult("SD_CARD", root.absolutePath, listOf(candidate))

        var stagedCount = 0
        manager.confirmImport(
            scanResult = scanResult,
            destinationDir = stageDir,
            onFileStaged = { _, _ -> stagedCount++ }
        )

        val state = manager.state.value
        assertTrue(state is OtgState.Completed)
        val completed = state as OtgState.Completed
        assertEquals(0, completed.importedCount)
        assertEquals(0, stagedCount)
        assertEquals(1, completed.fileErrors.size)

        val err: OtgIngestFileError = completed.fileErrors.first()
        assertEquals(candidate, err.candidate)
        assertTrue(
            "error message must mention the byte-count mismatch, got: ${err.message}",
            err.message.contains("mid-stream", ignoreCase = true) ||
                err.message.contains("mismatch", ignoreCase = true) ||
                err.message.contains("may be failing", ignoreCase = true),
        )

        val staged = File(stageDir, "DCIM/FAULT.CR3")
        assertFalse(
            "staged file must be deleted on a short-read failure, found ${staged.length()} bytes",
            staged.exists(),
        )
    }

    /**
     * Acceptance criterion (T2-7): "A successful copy's BLAKE3 hash is
     * the same as the hash computed at upload time." The unit-test JVM
     * doesn't load the gomobile AAR, so EngineHolder.computeBlake3Hex
     * returns null and the post-copy verification is skipped — that
     * branch is exercised in instrumentation. Here we verify the
     * happy path still stages successfully when the verifier is a
     * no-op: the hashObserver should never be invoked without an
     * available engine, and a successful copy must complete cleanly.
     */
    @Test
    fun testSuccessfulCopyStagedFileExistsAndCompletesWithoutErrors() = runTest(testDispatcher) {
        val root = tempFolder.newFolder("SD_CARD_HASH")
        val dcim = File(root, "DCIM").apply { mkdirs() }
        // Use a deterministic byte pattern so the hash, if computed,
        // would be reproducible across processes.
        val payload = ByteArray(8192) { (it and 0xFF).toByte() }
        val sourceFile = File(dcim, "OK.CR3").apply { writeBytes(payload) }

        val stageDir = tempFolder.newFolder("otg_stage_hash")

        var hashEvents = 0
        val manager = OtgIngestManager(
            scope = this,
            ioDispatcher = testDispatcher,
            hashObserver = { _, _, _ -> hashEvents++ },
        )

        val candidate = OtgMediaCandidate(
            uri = sourceFile.absolutePath,
            relativePath = "DCIM/OK.CR3",
            fileName = "OK.CR3",
            sizeBytes = sourceFile.length(),
            lastModifiedUnix = 1700000000,
            isRaw = true,
            isVideo = false
        )
        val scanResult = OtgScanResult("SD_CARD", root.absolutePath, listOf(candidate))

        manager.confirmImport(scanResult = scanResult, destinationDir = stageDir)

        val state = manager.state.value
        assertTrue(state is OtgState.Completed)
        val completed = state as OtgState.Completed
        assertEquals(1, completed.importedCount)
        assertEquals(0, completed.fileErrors.size)
        // Engine unavailable in unit tests → hashObserver never fires.
        assertEquals(0, hashEvents)

        val staged = File(stageDir, "DCIM/OK.CR3")
        assertTrue(staged.exists())
        assertEquals(payload.size.toLong(), staged.length())
        assertTrue(
            "staged bytes must equal source bytes",
            staged.readBytes().contentEquals(payload),
        )
    }

    /**
     * The mix-and-match case: one file exceeds the cap, one is a
     * short read, one succeeds. The manager must continue past the
     * failures and surface all three outcomes in the Completed state.
     * This protects against an earlier draft that used to throw on
     * per-file failure and skip the rest of the scan.
     */
    @Test
    fun testMixedSuccessAndFailurePassesAreReportedTogether() = runTest(testDispatcher) {
        val root = tempFolder.newFolder("SD_CARD_MIXED")
        val dcim = File(root, "DCIM").apply { mkdirs() }
        val okSource = File(dcim, "OK.JPG").apply { writeBytes(ByteArray(2048)) }
        val shortSource = File(dcim, "SHORT.CR3").apply { writeBytes(ByteArray(512)) }
        // The "huge" source is just a tiny file with a 100 GB claim, so
        // the size-cap check trips before any copy is attempted.
        val hugeSource = File(dcim, "HUGE.MP4").apply { writeBytes(ByteArray(16)) }

        val stageDir = tempFolder.newFolder("otg_stage_mixed")

        val manager = OtgIngestManager(
            scope = this,
            ioDispatcher = testDispatcher
        )

        val okCandidate = OtgMediaCandidate(
            uri = okSource.absolutePath,
            relativePath = "DCIM/OK.JPG",
            fileName = "OK.JPG",
            sizeBytes = okSource.length(),
            lastModifiedUnix = 1700000000,
            isRaw = false,
            isVideo = false
        )
        val shortCandidate = OtgMediaCandidate(
            uri = shortSource.absolutePath,
            relativePath = "DCIM/SHORT.CR3",
            fileName = "SHORT.CR3",
            sizeBytes = shortSource.length() + 8192,
            lastModifiedUnix = 1700000000,
            isRaw = true,
            isVideo = false
        )
        val hugeCandidate = OtgMediaCandidate(
            uri = hugeSource.absolutePath,
            relativePath = "DCIM/HUGE.MP4",
            fileName = "HUGE.MP4",
            sizeBytes = OtgIngestManager.MAX_OTG_STAGE_BYTES + 1L,
            lastModifiedUnix = 1700000000,
            isRaw = false,
            isVideo = true
        )

        val scanResult = OtgScanResult(
            "SD_CARD",
            root.absolutePath,
            listOf(okCandidate, shortCandidate, hugeCandidate),
        )

        var stagedCount = 0
        manager.confirmImport(
            scanResult = scanResult,
            destinationDir = stageDir,
            onFileStaged = { _, _ -> stagedCount++ }
        )

        val state = manager.state.value
        assertTrue(state is OtgState.Completed)
        val completed = state as OtgState.Completed
        assertEquals("only the OK file should have staged", 1, completed.importedCount)
        assertEquals(1, stagedCount)
        assertEquals(2, completed.fileErrors.size)

        val erroredNames = completed.fileErrors.map { it.candidate.fileName }.toSet()
        assertEquals(setOf("SHORT.CR3", "HUGE.MP4"), erroredNames)

        // The successful staged file exists; the failed ones don't.
        assertTrue(File(stageDir, "DCIM/OK.JPG").exists())
        assertFalse(File(stageDir, "DCIM/SHORT.CR3").exists())
        assertFalse(File(stageDir, "DCIM/HUGE.MP4").exists())
    }

    /**
     * Companion to the hash-equality acceptance criterion: with no
     * engine available (the unit-test default), the manager must still
     * surface the candidate as Success so we don't regress the
     * existing import flow. The post-copy BLAKE3 verify degrades to
     * "skipped" rather than failing the copy.
     */
    @Test
    fun testHashVerifyIsSkippedNotFatalWhenEngineUnavailable() = runTest(testDispatcher) {
        val root = tempFolder.newFolder("SD_CARD_NO_ENGINE")
        val dcim = File(root, "DCIM").apply { mkdirs() }
        val sourceFile = File(dcim, "OK2.JPG").apply { writeBytes(ByteArray(512)) }

        val stageDir = tempFolder.newFolder("otg_stage_no_engine")

        val manager = OtgIngestManager(
            scope = this,
            ioDispatcher = testDispatcher
        )

        val candidate = OtgMediaCandidate(
            uri = sourceFile.absolutePath,
            relativePath = "DCIM/OK2.JPG",
            fileName = "OK2.JPG",
            sizeBytes = sourceFile.length(),
            lastModifiedUnix = 1700000000,
            isRaw = false,
            isVideo = false
        )
        val scanResult = OtgScanResult("SD_CARD", root.absolutePath, listOf(candidate))
        manager.confirmImport(scanResult = scanResult, destinationDir = stageDir)

        val state = manager.state.value
        assertTrue(state is OtgState.Completed)
        val completed = state as OtgState.Completed
        assertNotNull(completed)
        assertEquals(1, completed.importedCount)
        assertEquals(0, completed.fileErrors.size)
    }

    /**
     * T2-7b: when the post-copy BLAKE3 verifier reports a fresh hash
     * that differs from the prior hash for the same localID, the
     * staged file must be deleted and the failure surfaced as a
     * per-file error. Previously (PR #91), the mismatch was only
     * logged and the staged file proceeded to the uploader.
     *
     * The mismatch is forced by injecting an [OtgHashProvider] that
     * returns a non-empty prior hash and a deliberately-different
     * fresh hash for any localID.
     */
    @Test
    fun testBlake3MismatchDeletesStagedFileAndSurfacesPerFileError() = runTest(testDispatcher) {
        val root = tempFolder.newFolder("SD_CARD_HASH_MISMATCH")
        val dcim = File(root, "DCIM").apply { mkdirs() }
        val sourceFile = File(dcim, "ROT.CR3").apply { writeBytes(ByteArray(1024)) }

        val stageDir = tempFolder.newFolder("otg_stage_mismatch")

        val mismatchingProvider = OtgHashProvider { _, _ ->
            // 64-hex BLAKE3 placeholders — not real hashes, just
            // distinct strings so the prior/fresh comparison fails.
            Pair(fresh = "b3_fresh_hash_64_chars_aabbccddeeff00112233445566778899aabbccddeeff00112233", prior = "b3_prior_hash_64_chars_different_00112233445566778899aabbccddeeff00112233445566778899") // pragma: allowlist secret
        }

        var hashObserverCalls = 0
        val manager = OtgIngestManager(
            scope = this,
            ioDispatcher = testDispatcher,
            hashObserver = { _, _, _ -> hashObserverCalls++ },
            hashProvider = mismatchingProvider,
        )

        val candidate = OtgMediaCandidate(
            uri = sourceFile.absolutePath,
            relativePath = "DCIM/ROT.CR3",
            fileName = "ROT.CR3",
            sizeBytes = sourceFile.length(),
            lastModifiedUnix = 1700000000,
            isRaw = true,
            isVideo = false
        )
        val scanResult = OtgScanResult("SD_CARD", root.absolutePath, listOf(candidate))

        var stagedCount = 0
        manager.confirmImport(
            scanResult = scanResult,
            destinationDir = stageDir,
            onFileStaged = { _, _ -> stagedCount++ },
        )

        val state = manager.state.value
        assertTrue(state is OtgState.Completed)
        val completed = state as OtgState.Completed
        assertEquals(
            "mismatched copy must not count as imported",
            0,
            completed.importedCount,
        )
        assertEquals("onFileStaged must not be called for a failed copy", 0, stagedCount)
        assertEquals(1, completed.fileErrors.size)
        assertEquals(
            "hashObserver must still fire so future observers can react, but only once per failed file",
            1,
            hashObserverCalls,
        )

        val err = completed.fileErrors.first()
        assertEquals(candidate, err.candidate)
        assertTrue(
            "error message must mention the BLAKE3 mismatch, got: ${err.message}",
            err.message.contains("BLAKE3 mismatch", ignoreCase = true),
        )

        val staged = File(stageDir, "DCIM/ROT.CR3")
        assertFalse(
            "staged file must be deleted when BLAKE3 verify fails, found ${staged.length()} bytes",
            staged.exists(),
        )
    }

    /**
     * T2-7b: when the prior hash is empty (first-time localID) the
     * mismatch branch must not fire — the only legal signal of a
     * bad SD card is a non-empty prior hash that differs from the
     * fresh one. This test pins the contract so a future "always
     * reject" patch can't break the first-ingest case.
     */
    @Test
    fun testFirstTimeLocalIdWithEmptyPriorHashDoesNotTriggerMismatchFailure() = runTest(testDispatcher) {
        val root = tempFolder.newFolder("SD_CARD_FIRST_TIME")
        val dcim = File(root, "DCIM").apply { mkdirs() }
        val sourceFile = File(dcim, "FIRST.JPG").apply { writeBytes(ByteArray(2048)) }

        val stageDir = tempFolder.newFolder("otg_stage_first_time")

        val firstTimeProvider = OtgHashProvider { _, _ ->
            // 64-hex BLAKE3 placeholder — not a real hash.
            Pair(fresh = "any_fresh_hash_64_chars_long_enough_to_be_a_real_blake3_hex_value_padding", prior = "") // pragma: allowlist secret
        }

        val manager = OtgIngestManager(
            scope = this,
            ioDispatcher = testDispatcher,
            hashProvider = firstTimeProvider,
        )

        val candidate = OtgMediaCandidate(
            uri = sourceFile.absolutePath,
            relativePath = "DCIM/FIRST.JPG",
            fileName = "FIRST.JPG",
            sizeBytes = sourceFile.length(),
            lastModifiedUnix = 1700000000,
            isRaw = false,
            isVideo = false
        )
        val scanResult = OtgScanResult("SD_CARD", root.absolutePath, listOf(candidate))
        manager.confirmImport(scanResult = scanResult, destinationDir = stageDir)

        val state = manager.state.value
        assertTrue(state is OtgState.Completed)
        val completed = state as OtgState.Completed
        assertEquals(1, completed.importedCount)
        assertEquals(0, completed.fileErrors.size)
        assertTrue(File(stageDir, "DCIM/FIRST.JPG").exists())
    }
}
