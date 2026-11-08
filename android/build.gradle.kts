plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so org.jetbrains.kotlin.android is
    // never applied. It is declared here, not applied, to pin the Kotlin Gradle plugin:
    // AGP 9.4.1 depends on KGP 2.2.10 and the compose plugin brings 2.4.21, so without a
    // declared version Gradle's highest-wins resolution would decide the compiler.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
