package com.branchdam.mobile

import com.branchdam.mobile.ui.AuditCandidate
import com.branchdam.mobile.ui.QrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UiStateTest {

    @Test
    fun testQrPayloadParsing() {
        val validPayload = "branchdam://server=http://192.168.1.50:8080&key=secret123&agent=pixel-fold"
        val config = QrParser.parseQrPayload(validPayload)

        assertNotNull(config)
        assertEquals("http://192.168.1.50:8080", config?.serverUrl)
        assertEquals("secret123", config?.apiKey)
        assertEquals("pixel-fold", config?.agentId)

        val invalidPayload = "https://other.domain.com"
        val invalidConfig = QrParser.parseQrPayload(invalidPayload)
        assertNull(invalidConfig)
    }

    @Test
    fun testAuditCandidateModel() {
        val candidate = AuditCandidate(
            edgeId = "edge-100",
            masterFilename = "PXL_20260829_001.dng",
            childFilename = "PXL_20260829_001.jpg",
            confidence = 1.00,
            resolver = "android_camera_pair"
        )

        assertEquals("edge-100", candidate.edgeId)
        assertEquals(1.00, candidate.confidence, 0.001)
    }
}
