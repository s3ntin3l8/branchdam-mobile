package com.branchdam.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun testValidHTTPS() {
        assertTrue(UrlValidator.isValidServerUrl("https://nas.example.com:8443", isDebug = false))
        assertTrue(UrlValidator.isValidServerUrl("https://example.com", isDebug = false))
    }

    @Test
    fun testValidHTTPDebug() {
        assertTrue(UrlValidator.isValidServerUrl("http://10.0.2.2:8080", isDebug = true))
        assertTrue(UrlValidator.isValidServerUrl("http://localhost:8080", isDebug = true))
        assertTrue(UrlValidator.isValidServerUrl("http://127.0.0.1:8080", isDebug = true))
        assertTrue(UrlValidator.isValidServerUrl("http://192.168.1.100:8080", isDebug = true))
    }

    @Test
    fun testHTTPBlockedInRelease() {
        assertFalse(UrlValidator.isValidServerUrl("http://10.0.2.2:8080", isDebug = false))
        assertFalse(UrlValidator.isValidServerUrl("http://localhost:8080", isDebug = false))
        assertFalse(UrlValidator.isValidServerUrl("http://192.168.1.100:8080", isDebug = false))
    }

    @Test
    fun testHTTPBlockedForUnknownHostInDebug() {
        assertFalse(UrlValidator.isValidServerUrl("http://attacker.example.com", isDebug = true))
        assertFalse(UrlValidator.isValidServerUrl("http://10.0.0.1:8080", isDebug = true))
    }

    @Test
    fun testRejectsDangerousSchemes() {
        assertFalse(UrlValidator.isValidServerUrl("javascript:alert(1)", isDebug = true))
        assertFalse(UrlValidator.isValidServerUrl("file:///etc/passwd", isDebug = true))
        assertFalse(UrlValidator.isValidServerUrl("data:text/html,<h1>xss</h1>", isDebug = true))
        assertFalse(UrlValidator.isValidServerUrl("ftp://example.com", isDebug = true))
    }

    @Test
    fun testRejectsEmptyAndBlank() {
        assertFalse(UrlValidator.isValidServerUrl("", isDebug = true))
        assertFalse(UrlValidator.isValidServerUrl("   ", isDebug = true))
    }

    @Test
    fun testRejectsMissingHost() {
        assertFalse(UrlValidator.isValidServerUrl("https://", isDebug = true))
        assertFalse(UrlValidator.isValidServerUrl("https:///path", isDebug = true))
    }

    @Test
    fun testRejectsNonURLStrings() {
        assertFalse(UrlValidator.isValidServerUrl("not-a-url", isDebug = true))
        assertFalse(UrlValidator.isValidServerUrl("://bad", isDebug = true))
        assertFalse(UrlValidator.isValidServerUrl("random text", isDebug = true))
    }
}
