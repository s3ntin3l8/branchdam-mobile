package com.branchdam.mobile

import android.content.IntentSender
import com.branchdam.mobile.triage.DeleteStep
import com.branchdam.mobile.triage.DirectDeleteOutcome
import com.branchdam.mobile.triage.MediaDeleteCoordinator
import com.branchdam.mobile.triage.MediaStoreDeleter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Tests for [MediaDeleteCoordinator]: the ViewModel-side state machine
 * that turns a delete needing the user's confirmation into a pending
 * request the Composable launches exactly once, and resumes on the
 * Activity result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaDeleteCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()
    private val sender: IntentSender = mock()

    private fun api30Deleter() = MediaStoreDeleter(
        sdkInt = 34,
        directDelete = { error("API 30+ must not call ContentResolver.delete") },
        createDeleteRequest = { sender },
    )

    @Test
    fun consentIsExposedLaunchedOnceAndResumedOnResult() = runTest(dispatcher) {
        val coordinator = MediaDeleteCoordinator(this) { dispatcher }
        var result: DeleteStep.Done? = null

        assertTrue(coordinator.delete(api30Deleter(), listOf("a", "b")) { result = it })
        advanceUntilIdle()

        val pending = coordinator.pendingConsent.value
        assertNotNull("API 30+ must surface a confirmation", pending)
        pending!!
        assertEquals(2, pending.itemCount)
        assertNull("nothing is final until the user answers", result)

        // Two screens observing the same ViewModel: only one may launch.
        assertTrue(coordinator.onConsentLaunched(pending.id))
        assertFalse(coordinator.onConsentLaunched(pending.id))
        assertTrue(coordinator.pendingConsent.value!!.launched)

        coordinator.onConsentResult(approved = true)
        advanceUntilIdle()

        assertNull(coordinator.pendingConsent.value)
        assertEquals(DeleteStep.Done(setOf("a", "b"), emptySet()), result)
        assertFalse(coordinator.isBusy)
    }

    @Test
    fun declinedConsentReportsEverythingFailed() = runTest(dispatcher) {
        val coordinator = MediaDeleteCoordinator(this) { dispatcher }
        var result: DeleteStep.Done? = null

        coordinator.delete(api30Deleter(), listOf("a")) { result = it }
        advanceUntilIdle()
        coordinator.onConsentResult(approved = false)
        advanceUntilIdle()

        assertEquals(DeleteStep.Done(emptySet(), setOf("a")), result)
    }

    @Test
    fun secondDeleteIsRejectedWhileOneAwaitsTheUser() = runTest(dispatcher) {
        val coordinator = MediaDeleteCoordinator(this) { dispatcher }

        assertTrue(coordinator.delete(api30Deleter(), listOf("a")) {})
        advanceUntilIdle()
        assertTrue(coordinator.isBusy)
        assertFalse(coordinator.delete(api30Deleter(), listOf("b")) { error("must not run") })
    }

    @Test
    fun directDeleteFinishesWithoutConsent() = runTest(dispatcher) {
        val coordinator = MediaDeleteCoordinator(this) { dispatcher }
        var result: DeleteStep.Done? = null
        val deleter = MediaStoreDeleter(
            sdkInt = 28,
            directDelete = { DirectDeleteOutcome.Deleted },
            createDeleteRequest = { error("createDeleteRequest is API 30+") },
        )

        coordinator.delete(deleter, listOf("a")) { result = it }
        advanceUntilIdle()

        assertNull(coordinator.pendingConsent.value)
        assertEquals(DeleteStep.Done(setOf("a"), emptySet()), result)
    }

    @Test
    fun resultWithoutPendingRequestIsIgnored() {
        val coordinator = MediaDeleteCoordinator(TestScope(dispatcher)) { dispatcher }
        coordinator.onConsentResult(approved = true)
        assertNull(coordinator.pendingConsent.value)
        assertFalse(coordinator.isBusy)
    }
}
