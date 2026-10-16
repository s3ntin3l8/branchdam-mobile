package com.branchdam.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Release builds permit no cleartext traffic at all; debug builds permit
 * it to exactly the development hosts the URL validator and the Go engine
 * allow, matched exactly (no subdomains).
 */
class NetworkSecurityConfigTest {

    private fun config(sourceSet: String): Element {
        val rel = "src/$sourceSet/res/xml/network_security_config.xml"
        // Gradle runs unit tests from the module directory (android/app).
        val file = listOf(File(rel), File("app/$rel"), File("android/app/$rel")).first { it.exists() }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun releaseConfigAllowsNoCleartext() {
        val root = config("main")
        val base = root.children("base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        assertTrue("no per-domain exceptions in release", root.children("domain-config").isEmpty())
        val anchors = root.children("certificates").map { it.getAttribute("src") }
        assertEquals("release trusts the system CAs only", listOf("system"), anchors)
    }

    @Test
    fun debugConfigAllowsExactlyTheDevHosts() {
        val root = config("debug")
        assertEquals("false", root.children("base-config").single().getAttribute("cleartextTrafficPermitted"))
        val cleartext = root.children("domain-config").filter { it.getAttribute("cleartextTrafficPermitted") == "true" }
        val domains = cleartext.flatMap { it.children("domain") }
        assertEquals(UrlValidator.DEV_CLEARTEXT_HOSTS.toSet(), domains.map { it.textContent.trim() }.toSet())
        assertTrue("dev hosts are matched exactly", domains.all { it.getAttribute("includeSubdomains") == "false" })
    }
}
