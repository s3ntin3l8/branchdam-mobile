package com.branchdam.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Start decision and outcome mapping behind onboarding's "Grant Access" page. */
class OnboardingPermissionTest {

    @Test
    fun startAllowedBeforeTheSequenceHasBegun() {
        assertTrue(shouldStartPermissionSequence(PermissionBatch.NONE))
    }

    @Test
    fun startRefusedWhileASequenceIsRunning() {
        assertFalse(shouldStartPermissionSequence(PermissionBatch.NOTIFICATIONS))
        assertFalse(shouldStartPermissionSequence(PermissionBatch.MEDIA))
    }

    @Test
    fun startAllowedAfterAFinishedSequenceSoDenialCanBeRetried() {
        assertTrue(shouldStartPermissionSequence(PermissionBatch.DONE))
    }

    @Test
    fun startBeginsAtNotificationsFromNoneAndDone() {
        for (from in listOf(PermissionBatch.NONE, PermissionBatch.DONE)) {
            val flow = PermissionFlowState()
            if (from == PermissionBatch.DONE) flow.markDone()
            flow.start()
            assertEquals(PermissionBatch.NOTIFICATIONS, flow.batch.value)
        }
    }

    @Test
    fun startLeavesARunningSequenceAlone() {
        val flow = PermissionFlowState()
        flow.nextBatch()
        flow.nextBatch()
        assertEquals(PermissionBatch.MEDIA, flow.batch.value)
        flow.start()
        assertEquals(PermissionBatch.MEDIA, flow.batch.value)
    }

    @Test
    fun fullAccessIsGrantedInAnyState() {
        for (b in PermissionBatch.values()) {
            assertEquals(OnboardingAccess.GRANTED, onboardingAccess(MediaAccessLevel.FULL, b))
        }
    }

    @Test
    fun limitedAccessIsLimitedInAnyState() {
        for (b in PermissionBatch.values()) {
            assertEquals(OnboardingAccess.LIMITED, onboardingAccess(MediaAccessLevel.LIMITED, b))
        }
    }

    @Test
    fun missingAccessIsDeniedOnlyOnceTheSequenceFinished() {
        assertEquals(OnboardingAccess.DENIED, onboardingAccess(MediaAccessLevel.DENIED, PermissionBatch.DONE))
        for (b in listOf(PermissionBatch.NONE, PermissionBatch.NOTIFICATIONS, PermissionBatch.MEDIA)) {
            assertEquals(OnboardingAccess.PENDING, onboardingAccess(MediaAccessLevel.DENIED, b))
        }
    }
}
