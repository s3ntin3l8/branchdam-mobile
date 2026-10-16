package com.branchdam.mobile

import android.content.IntentSender
import com.branchdam.mobile.triage.DeleteStep
import com.branchdam.mobile.triage.DeleteStrategy
import com.branchdam.mobile.triage.DirectDeleteOutcome
import com.branchdam.mobile.triage.MediaStoreDeleter
import com.branchdam.mobile.triage.deleteStrategyFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Tests for the per-API-level delete branching in [MediaStoreDeleter].
 * Scoped storage (API 29+) refuses a plain `ContentResolver.delete` on
 * camera-roll items the app did not create, so:
 *  - API 28 deletes directly,
 *  - API 29 asks per item via RecoverableSecurityException and retries,
 *  - API 30+ asks once for the batch via createDeleteRequest, and the
 *    system performs the delete itself.
 * The platform calls are injected, so these run on the plain JVM.
 */
class MediaStoreDeleterTest {

    private val sender: IntentSender = mock()

    @Test
    fun strategyPerApiLevel() {
        assertEquals(DeleteStrategy.DIRECT, deleteStrategyFor(28))
        assertEquals(DeleteStrategy.RECOVERABLE_SECURITY_EXCEPTION, deleteStrategyFor(29))
        assertEquals(DeleteStrategy.CREATE_DELETE_REQUEST, deleteStrategyFor(30))
        assertEquals(DeleteStrategy.CREATE_DELETE_REQUEST, deleteStrategyFor(35))
    }

    @Test
    fun emptyBatchIsDoneWithoutTouchingMediaStore() {
        val deleter = MediaStoreDeleter(
            sdkInt = 34,
            directDelete = { error("must not delete") },
            createDeleteRequest = { error("must not build a request") },
        )
        assertEquals(DeleteStep.Done(emptySet(), emptySet()), deleter.start(emptyList()))
    }

    @Test
    fun api28DeletesDirectlyAndRecordsFailures() {
        val attempted = mutableListOf<String>()
        val deleter = MediaStoreDeleter(
            sdkInt = 28,
            directDelete = { uri ->
                attempted += uri
                if (uri == "u2") DirectDeleteOutcome.NotDeleted else DirectDeleteOutcome.Deleted
            },
            createDeleteRequest = { error("createDeleteRequest is API 30+") },
        )

        val step = deleter.start(listOf("u1", "u2", "u3", "u1"))

        assertEquals(DeleteStep.Done(setOf("u1", "u3"), setOf("u2")), step)
        assertEquals("duplicates are attempted once", listOf("u1", "u2", "u3"), attempted)
    }

    @Test
    fun api29PausesForConsentAndRetriesAfterApproval() {
        val granted = mutableSetOf<String>()
        val deleter = MediaStoreDeleter(
            sdkInt = 29,
            directDelete = { uri ->
                when {
                    uri == "owned" -> DirectDeleteOutcome.Deleted
                    uri in granted -> DirectDeleteOutcome.Deleted
                    else -> DirectDeleteOutcome.NeedsConsent(sender)
                }
            },
            createDeleteRequest = { error("createDeleteRequest is API 30+") },
        )

        val first = deleter.start(listOf("owned", "a", "b"))
        assertTrue(first is DeleteStep.NeedsConsent)
        first as DeleteStep.NeedsConsent
        assertSame(sender, first.request.intentSender)
        assertEquals(listOf("a"), first.request.uris)
        assertTrue("API 29 must retry the delete after approval", first.request.retryDeleteAfterApproval)
        assertEquals(setOf("owned"), first.deletedSoFar)
        assertEquals(listOf("b"), first.remaining)

        // The user approves "a": write access is granted, the delete is
        // retried, and the next item asks again.
        granted += "a"
        val second = deleter.resume(first, approved = true)
        assertTrue(second is DeleteStep.NeedsConsent)
        second as DeleteStep.NeedsConsent
        assertEquals(listOf("b"), second.request.uris)
        assertEquals(setOf("owned", "a"), second.deletedSoFar)

        granted += "b"
        assertEquals(
            DeleteStep.Done(setOf("owned", "a", "b"), emptySet()),
            deleter.resume(second, approved = true),
        )
    }

    @Test
    fun api29DeclineFailsTheItemAndEverythingAfterIt() {
        val deleter = MediaStoreDeleter(
            sdkInt = 29,
            directDelete = { DirectDeleteOutcome.NeedsConsent(sender) },
            createDeleteRequest = { error("createDeleteRequest is API 30+") },
        )
        val first = deleter.start(listOf("a", "b", "c")) as DeleteStep.NeedsConsent

        assertEquals(
            DeleteStep.Done(emptySet(), setOf("a", "b", "c")),
            deleter.resume(first, approved = false),
        )
    }

    @Test
    fun api29RetryThatStillFailsIsRecordedAsFailed() {
        val deleter = MediaStoreDeleter(
            sdkInt = 29,
            directDelete = { DirectDeleteOutcome.NeedsConsent(sender) },
            createDeleteRequest = { error("createDeleteRequest is API 30+") },
        )
        val first = deleter.start(listOf("a")) as DeleteStep.NeedsConsent

        // Approved, but the retried delete is refused again.
        assertEquals(DeleteStep.Done(emptySet(), setOf("a")), deleter.resume(first, approved = true))
    }

    @Test
    fun api30AsksOnceForTheWholeBatch() {
        var requested: List<String>? = null
        val deleter = MediaStoreDeleter(
            sdkInt = 30,
            directDelete = { error("API 30+ must not call ContentResolver.delete") },
            createDeleteRequest = { uris ->
                requested = uris
                sender
            },
        )

        val step = deleter.start(listOf("a", "b", "a"))

        assertTrue(step is DeleteStep.NeedsConsent)
        step as DeleteStep.NeedsConsent
        assertEquals(listOf("a", "b"), requested)
        assertEquals(listOf("a", "b"), step.request.uris)
        assertFalse("the system deletes on approval", step.request.retryDeleteAfterApproval)
        assertTrue(step.remaining.isEmpty())

        assertEquals(DeleteStep.Done(setOf("a", "b"), emptySet()), deleter.resume(step, approved = true))
        assertEquals(DeleteStep.Done(emptySet(), setOf("a", "b")), deleter.resume(step, approved = false))
    }

    @Test
    fun api30RequestThatCannotBeBuiltFailsTheBatch() {
        val deleter = MediaStoreDeleter(
            sdkInt = 34,
            directDelete = { error("API 30+ must not call ContentResolver.delete") },
            createDeleteRequest = { throw IllegalArgumentException("not a MediaStore item") },
        )
        assertEquals(DeleteStep.Done(emptySet(), setOf("a", "b")), deleter.start(listOf("a", "b")))
    }
}
