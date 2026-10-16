package com.branchdam.mobile

import com.branchdam.mobile.lineage.LineageReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who decides a lineage pair: exact (1.00) pairs are sent automatically
 * (AGENTS.md invariant 4), anything weaker waits for the user. Robolectric
 * supplies a real org.json for the decision-list parsing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LineageReviewTest {

    @Test
    fun onlyExactPairsAreAutomatic() {
        assertTrue(LineageReview.isAutomatic(1.00))
        assertFalse(LineageReview.isAutomatic(0.95))
        assertFalse(LineageReview.isAutomatic(0.9999))
    }

    @Test
    fun pairKeyIsParentFirst() {
        assertEquals("content://a|content://b", LineageReview.pairKey("content://a", "content://b"))
    }

    @Test
    fun parsesTheEngineDecisionList() {
        val json = """
            [
              {"parentLocalId":"content://raw/1","childLocalId":"content://jpg/2","decision":"REJECTED","confidence":0.95,"decidedAtUnix":1},
              {"parentLocalId":"ph://a","childLocalId":"ph://b","decision":"CONFIRMED","confidence":0.95,"eventUuid":"e","decidedAtUnix":2},
              {"parentLocalId":"","childLocalId":"x","decision":"REJECTED"}
            ]
        """.trimIndent()
        assertEquals(
            setOf("content://raw/1|content://jpg/2", "ph://a|ph://b"),
            LineageReview.parseDecidedKeys(json),
        )
        assertEquals(emptySet<String>(), LineageReview.parseDecidedKeys("[]"))
        assertEquals(emptySet<String>(), LineageReview.parseDecidedKeys(""))
    }
}
