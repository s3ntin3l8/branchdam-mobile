package com.branchdam.mobile

import com.branchdam.mobile.ui.QrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [QrParser.parseQrPayload] — the pure function inside
 * QrPairingScreen.kt that parses a `branchdam://` URL into a
 * PairingConfig. The Compose UI around it requires
 * androidx.compose.ui.test infrastructure which is not on the unit
 * test classpath; the parser itself is the testable surface.
 */
class QrParserTest {

    @Test
    fun testParseHappyPath() {
        val config = QrParser.parseQrPayload(
            "branchdam://server=http://192.168.1.100:8080&key=abc123&agent=pixel-10"
        )
        assertNotNull(config)
        assertEquals("http://192.168.1.100:8080", config!!.serverUrl)
        assertEquals("abc123", config.apiKey)
        assertEquals("pixel-10", config.agentId)
    }

    @Test
    fun testParseDefaultsAgentToPixelFold() {
        val config = QrParser.parseQrPayload(
            "branchdam://server=http://10.0.2.2:8080&key=secret"
        )
        assertNotNull(config)
        assertEquals("http://10.0.2.2:8080", config!!.serverUrl)
        assertEquals("secret", config.apiKey)
        assertEquals("pixel-fold", config.agentId)
    }

    @Test
    fun testParseEmptyApiKey() {
        val config = QrParser.parseQrPayload(
            "branchdam://server=http://example.com:8080&key=&agent=test"
        )
        assertNotNull(config)
        assertEquals("", config!!.apiKey)
    }

    @Test
    fun testParseRejectsNonBranchdamScheme() {
        assertNull(QrParser.parseQrPayload("https://server=http://example.com"))
        assertNull(QrParser.parseQrPayload(""))
        assertNull(QrParser.parseQrPayload("not a url at all"))
    }

    @Test
    fun testParseRejectsMissingServer() {
        // No `server` param → null.
        assertNull(QrParser.parseQrPayload("branchdam://key=abc&agent=test"))
    }

    @Test
    fun testParseIgnoresUnknownParams() {
        // Unknown params are silently ignored; known params are parsed.
        val config = QrParser.parseQrPayload(
            "branchdam://server=http://x:8080&unknown=value&key=k&another=foo"
        )
        assertNotNull(config)
        assertEquals("http://x:8080", config!!.serverUrl)
        assertEquals("k", config.apiKey)
    }

    @Test
    fun testParseAcceptsQueryStyleForm() {
        // README documents the spec form as `branchdam://?server=…&key=…&agent=…`
        // — the literal `?` separator after the scheme. Parser must accept both
        // `branchdam://server=…` and `branchdam://?server=…`.
        val config = QrParser.parseQrPayload(
            "branchdam://?server=http://192.168.1.100:8080&key=abc123&agent=pixel-10-fold"
        )
        assertNotNull(config)
        assertEquals("http://192.168.1.100:8080", config!!.serverUrl)
        assertEquals("abc123", config.apiKey)
        assertEquals("pixel-10-fold", config.agentId)
    }
}
