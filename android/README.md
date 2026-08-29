# Android App Module

Native Android companion module for branchDAM built with Jetpack Compose, Kotlin Coroutines, and WorkManager.

## Architecture

- **UI Layer:** Jetpack Compose with adaptive foldable layouts optimized for the Google Pixel 10 Pro Fold.
- **Background Ingest:** Android `WorkManager` periodic workers executing camera roll sync over unmetered Wi-Fi during charging.
- **Native Core:** Interfaced with Go core engine via JNI bindings.

## Build Commands

```bash
# Debug APK
./gradlew assembleDebug

# Release APK & AAB
./gradlew assembleRelease bundleRelease

# Unit Tests
./gradlew test
```
