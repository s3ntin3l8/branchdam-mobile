plugins {
    id("com.android.application") version "9.4.1" apply false
    // No org.jetbrains.kotlin.android: AGP 9 compiles Kotlin itself (built-in Kotlin).
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
