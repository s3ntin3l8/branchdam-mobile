package com.branchdam.mobile

import com.branchdam.mobile.triage.SafeSpaceVerdict
import com.branchdam.mobile.triage.parseSafeSpaceVerdicts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parsing of `BindingCheckSafeSpaceCandidates`'s JSON (a JSON array of
 * `SafeSpaceVerdict` objects, see branchdam.go). Robolectric supplies a
 * real org.json; the module-wide `isReturnDefaultValues` stub would
 * return empty values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeSpaceVerdictParsingTest {

    @Test
    fun parsesTheEngineVerdictArray() {
        val json = """
            [
              {"localId":"content://media/external/images/media/1","eligible":true},
              {"localId":"content://media/external/images/media/2","eligible":false,
               "reason":"VERIFIED_REQUIRED: node is not on the Tier-3 master archive"}
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                SafeSpaceVerdict("content://media/external/images/media/1", eligible = true, reason = null),
                SafeSpaceVerdict(
                    "content://media/external/images/media/2",
                    eligible = false,
                    reason = "VERIFIED_REQUIRED: node is not on the Tier-3 master archive",
                ),
            ),
            parseSafeSpaceVerdicts(json),
        )
    }

    @Test
    fun skipsEntriesWithoutALocalIdAndDefaultsToIneligible() {
        val json = """[{"eligible":true},{"localId":"x"},"junk"]"""
        assertEquals(listOf(SafeSpaceVerdict("x", eligible = false)), parseSafeSpaceVerdicts(json))
    }

    @Test
    fun emptyOrBlankIsNoVerdicts() {
        assertTrue(parseSafeSpaceVerdicts(null).isEmpty())
        assertTrue(parseSafeSpaceVerdicts("").isEmpty())
        assertTrue(parseSafeSpaceVerdicts("[]").isEmpty())
    }
}
