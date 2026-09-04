package com.branchdam.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchDamKeysTest {

    @Test
    fun testPrefNameIsStable() {
        assertEquals("branchdam_prefs", BranchDamKeys.PREFS_NAME)
    }

    @Test
    fun testKeysHaveBranchdamPrefix() {
        // iOS parity: keys must match the prefix the iOS shell uses,
        // so a future migration tool can target the same string on
        // both platforms. If a future PR adds a key without the
        // prefix, this assertion fires.
        for (key in listOf(
            BranchDamKeys.PREFS_NAME,
            BranchDamKeys.SYNC_ON_MOBILE_DATA,
            BranchDamKeys.AUTO_IMPORT_CAMERA_ROLL,
            BranchDamKeys.LAST_SYNC_TIME,
        )) {
            assertTrue(
                "key '$key' must start with 'branchdam_'",
                key.startsWith("branchdam_"),
            )
        }
    }

    @Test
    fun testLastSyncTimeKey() {
        assertEquals("branchdam_last_sync_time", BranchDamKeys.LAST_SYNC_TIME)
    }
}
