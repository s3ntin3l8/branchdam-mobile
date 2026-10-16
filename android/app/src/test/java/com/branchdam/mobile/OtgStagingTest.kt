package com.branchdam.mobile

import com.branchdam.mobile.otg.OtgStaging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Tests for [OtgStaging]: session layout, path safety and the cleanup sweep. */
class OtgStagingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun newSessionDirNeverReusesAnExistingDirectory() {
        val root = tempFolder.newFolder("otg_staging")
        val first = OtgStaging.newSessionDir(root, nowMs = 1000L).apply { mkdirs() }
        val second = OtgStaging.newSessionDir(root, nowMs = 1000L)
        assertNotEquals(first, second)
        assertFalse(second.exists())
        assertEquals(root, second.parentFile)
    }

    @Test
    fun stagedFileKeepsTheCardLayoutInsideTheSession() {
        val session = File(tempFolder.root, "s1")
        assertEquals(File(session, "DCIM/100CANON/IMG_0001.CR3"), OtgStaging.stagedFile(session, "DCIM/100CANON/IMG_0001.CR3"))
    }

    @Test
    fun stagedFileRejectsPathsThatEscapeTheSession() {
        val session = File(tempFolder.root, "s1")
        for (bad in listOf("", "/etc/passwd", "../x.jpg", "DCIM/../../x.jpg", "DCIM/./x.jpg", "DCIM//x.jpg", "\\x.jpg")) {
            assertNull(bad, OtgStaging.stagedFile(session, bad))
        }
    }

    @Test
    fun uploadedCopiesAreDeletedWhilePendingOnesAreKept() {
        val now = 100L * day
        assertTrue(OtgStaging.shouldDelete("a.cr3", "COMPLETED", now, now, engineAnswered = true))
        assertTrue(OtgStaging.shouldDelete("a.cr3", "OFFLOADED", now, now, engineAnswered = true))
        for (status in listOf("PENDING", "IN_PROGRESS", "FAILED")) {
            assertFalse(status, OtgStaging.shouldDelete("a.cr3", status, 0L, now, engineAnswered = true))
        }
    }

    @Test
    fun unknownCopiesAreKeptWhenTheEngineDidNotAnswer() {
        val now = 100L * day
        // An empty status map is also what a failed engine query returns:
        // the copy may be the only one left once the card is reformatted.
        assertFalse(OtgStaging.shouldDelete("a.cr3", null, 0L, now, engineAnswered = false))
        assertTrue(OtgStaging.shouldDelete("a.cr3", null, 0L, now, engineAnswered = true))
        assertFalse(OtgStaging.shouldDelete("a.cr3", null, now - day, now, engineAnswered = true))
    }

    @Test
    fun abandonedPartFilesExpire() {
        val now = 100L * day
        assertFalse(OtgStaging.shouldDelete("a.cr3.part", null, now - 1000L, now, engineAnswered = false))
        assertTrue(OtgStaging.shouldDelete("a.cr3.part", null, now - OtgStaging.PART_GRACE_MS - 1, now, engineAnswered = false))
    }

    @Test
    fun sweepRemovesCompletedCopiesAndEmptySessions() {
        val root = tempFolder.newFolder("otg_staging")
        val old = 1_000_000L
        val now = old + 2 * OtgStaging.PART_GRACE_MS
        val done = File(root, "s1/DCIM/100CANON/IMG_0001.CR3").apply { parentFile!!.mkdirs(); writeText("a") }
        val pending = File(root, "s2/DCIM/IMG_0002.CR3").apply { parentFile!!.mkdirs(); writeText("b") }
        val emptySession = File(root, "s0").apply { mkdirs() }
        listOf(done, pending, emptySession).forEach { it.setLastModified(old) }

        val removed = OtgStaging.sweep(
            root,
            mapOf(done.absolutePath to "COMPLETED", pending.absolutePath to "PENDING"),
            now,
        )

        assertEquals(1, removed)
        assertFalse(done.exists())
        assertTrue(pending.exists())
        assertFalse("an old empty session directory is pruned", emptySession.exists())
    }

    @Test
    fun sweepKeepsAFreshEmptySessionDirectory() {
        val root = tempFolder.newFolder("otg_staging")
        val fresh = File(root, "s9").apply { mkdirs() }
        OtgStaging.sweep(root, emptyMap(), nowMs = fresh.lastModified() + 1000L)
        assertTrue(fresh.exists())
    }

    @Test
    fun sweepOnMissingRootIsANoOp() {
        assertEquals(0, OtgStaging.sweep(File(tempFolder.root, "missing"), mapOf("x" to "COMPLETED")))
    }
}
