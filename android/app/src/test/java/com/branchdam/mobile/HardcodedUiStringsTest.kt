package com.branchdam.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the string extraction: the screens below take every user-facing
 * text from res/values/strings.xml, so a new hard-coded literal in a Text,
 * a content description or a title/label argument fails here instead of
 * shipping untranslatable.
 *
 * View-model messages are not scanned here: they come from resources via
 * the application Context, or (OtgIngestManager, which JVM tests build
 * without a Context) via a resource with an English fallback.
 */
class HardcodedUiStringsTest {

    private val extractedScreens = listOf(
        "ui/AuditQueueScreen.kt",
        "ui/OtgCardAccess.kt",
        "ui/OtgImportConfirmationDialog.kt",
        "ui/gallery/GalleryScreen.kt",
        "ui/gallery/GalleryDetailScreen.kt",
        "ui/lineage/LineageScreen.kt",
        "ui/navigation/NavigationItems.kt",
        "ui/navigation/NavigationSuite.kt",
        "ui/onboarding/OnboardingScreen.kt",
        "ui/qrscan/QrScanScreen.kt",
        "ui/safespace/SafeSpaceScreen.kt",
        "ui/settings/AdvancedSettingsScreen.kt",
        "ui/settings/AppearanceSettingsScreen.kt",
        "ui/settings/ConnectionSettingsScreen.kt",
        "ui/settings/ImportSettingsScreen.kt",
        "ui/settings/SettingsScreen.kt",
        "ui/settings/SyncSettingsScreen.kt",
        "ui/sync/SyncStatusScreen.kt",
    )

    /** A string literal passed where the user will see or hear it. */
    private val literalArgument = Regex(
        """(?:\bText\(\s*|contentDescription\s*=\s*|\b(?:title|subtitle|description|label|text)\s*=\s*|NavigationItem\(|SettingsCategoryHeader\(|SettingsSubHeader\(|DetailRow\(\s*(?:label\s*=\s*)?)"((?:[^"\\]|\\.)*)"""",
    )
    private val interpolation = Regex("""\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*""")

    /**
     * True when [code] passes a literal with words in it. Interpolated
     * values are ignored ("$roleLabel • $formatBadge" only joins values),
     * and so are lowercase identifiers without spaces, which are animation
     * or semantics labels ("sync_button_content"), not text.
     */
    private fun hasUserFacingLiteral(code: String): Boolean =
        literalArgument.findAll(code).any { match ->
            val text = interpolation.replace(match.groupValues[1], "")
            Regex("[A-Za-z]{2}").containsMatchIn(text) && (text.any { it.isUpperCase() } || text.any { it.isWhitespace() })
        }

    private fun sourceRoot(): File {
        // Gradle runs unit tests from the module directory (android/app).
        val candidates = listOf("src/main/java/com/branchdam/mobile", "app/src/main/java/com/branchdam/mobile")
        return candidates.map(::File).firstOrNull { it.isDirectory }
            ?: error("source root not found from ${File(".").absolutePath}")
    }

    @Test
    fun extractedScreensHaveNoHardcodedUserFacingStrings() {
        val root = sourceRoot()
        val offenders = mutableListOf<String>()
        for (path in extractedScreens) {
            val file = File(root, path)
            assertTrue("missing $file", file.isFile)
            file.readLines().forEachIndexed { index, line ->
                val code = line.substringBefore("//")
                if (hasUserFacingLiteral(code)) {
                    offenders += "$path:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            "hard-coded user-facing strings (move them to strings.xml):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun theGuardCatchesALiteral() {
        assertTrue(hasUserFacingLiteral("""Text("Sync Now")"""))
        assertTrue(hasUserFacingLiteral("""contentDescription = "Back""""))
        assertTrue(hasUserFacingLiteral("""title = "Connection","""))
        assertTrue(hasUserFacingLiteral("""text = "Item ${'$'}{a.index} of ${'$'}{a.total}","""))
        assertTrue(!hasUserFacingLiteral("""Text(stringResource(R.string.sync_now))"""))
        assertTrue(!hasUserFacingLiteral("""label = "sync_button_content""""))
        assertTrue(!hasUserFacingLiteral("""text = "${'$'}roleLabel • ${'$'}formatBadge","""))
    }
}
