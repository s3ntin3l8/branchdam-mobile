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
    fun testParseRejectsEmptyServer() {
        // Mirrors the iOS testParseRejectsEmptyServer — pins that an
        // explicit empty server value is treated as a hard parse
        // failure rather than yielding a PairingConfig with an empty
        // serverUrl.
        assertNull(QrParser.parseQrPayload("branchdam://server=&key=abc"))
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

    @Test
    fun testParseDecodesPercentEncodedServer() {
        // Mirrors the exact payload the server emits via url.Values.Encode():
        // `://` becomes `%3A%2F%2F`. Without decoding the parser used to
        // hand the raw encoded string to the engine, which failed to parse it.
        val config = QrParser.parseQrPayload(
            "branchdam://?server=https%3A%2F%2Fdam.example.com&key=abc123&agent=pixel-10"
        )
        assertNotNull(config)
        assertEquals("https://dam.example.com", config!!.serverUrl)
        assertEquals("abc123", config.apiKey)
        assertEquals("pixel-10", config.agentId)
    }

    @Test
    fun testParseDecodesServerWithPort() {
        // Port-bearing host: the `:` after the host is encoded to `%3A`
        // by the server. The parser must decode it back.
        val config = QrParser.parseQrPayload(
            "branchdam://?server=https%3A%2F%2Fdam.example.com%3A8443&key=secret&agent=phone"
        )
        assertNotNull(config)
        assertEquals("https://dam.example.com:8443", config!!.serverUrl)
        assertEquals("secret", config.apiKey)
        assertEquals("phone", config.agentId)
    }

    @Test
    fun testParseRejectsMalformedPercentEncoding() {
        // Stray `%` not followed by two hex digits → URLDecoder throws
        // → parser must return null rather than silently passing through
        // a malformed string.
        // `%ZZ` — non-hex characters. `%` alone — nothing follows. `%2` — single hex digit.
        assertNull(QrParser.parseQrPayload("branchdam://?server=https%ZZdam.example.com&key=abc&agent=test"))
        assertNull(QrParser.parseQrPayload("branchdam://?server=https%&key=abc&agent=test"))
        assertNull(QrParser.parseQrPayload("branchdam://?server=https%2&key=abc&agent=test"))
    }

    @Test
    fun testParseHandlesApiKeyContainingEquals() {
        // Regression guard: limit=2 on the inner split so a `=` inside
        // a percent-encoded API key is preserved as part of the value.
        val config = QrParser.parseQrPayload(
            "branchdam://?server=https%3A%2F%2Fx&key=abc%3Ddef&agent=test"
        )
        assertNotNull(config)
        assertEquals("https://x", config!!.serverUrl)
        assertEquals("abc=def", config.apiKey)
    }

    @Test
    fun testParseEncodedAndUnencodedFormsAgree() {
        // The two payload shapes (encoded by the server vs. unencoded in
        // some test fixtures) must yield identical PairingConfig values.
        val encoded = QrParser.parseQrPayload(
            "branchdam://?server=https%3A%2F%2Fdam.example.com&key=abc&agent=phone"
        )
        val unencoded = QrParser.parseQrPayload(
            "branchdam://?server=https://dam.example.com&key=abc&agent=phone"
        )
        assertNotNull(encoded)
        assertNotNull(unencoded)
        assertEquals(unencoded!!.serverUrl, encoded!!.serverUrl)
        assertEquals(unencoded.apiKey, encoded.apiKey)
        assertEquals(unencoded.agentId, encoded.agentId)
    }
}
