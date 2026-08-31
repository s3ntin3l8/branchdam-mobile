# branchdam-mobile

Native mobile companion application for [branchDAM](https://github.com/s3ntin3l8/branchdam), engineered for Android (optimized for Google Pixel Fold & flagships) and iOS.

---

## Features

- **Automated Capture Ingest:** Background camera roll sync over Wi-Fi when charging via Android WorkManager and iOS BackgroundTasks.
- **Server-Governed Direct Streaming:** Uploads directly to `POST /api/v1/agent/upload` with streaming chunked HTTP bodies, `X-Camera-Model` headers, and server-owned archive path resolution.
- **Dynamic Naming Template Sync:** Handshakes with the server to fetch and respect centralized naming templates (`{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}`).
- **Lineage Pairing (Confidence 1.00):** Automatic pairing of computational RAW (DNG) + Ultra HDR JPEG assets and Motion Photos.
- **Safe Space Reclaim:** Reclaims local device storage only after masters are durably archived and BLAKE3-verified on Tier 3 storage.
- **Bi-Directional Trash Sync:** System photo deletions propagate cleanly to Immich external libraries.
- **Foldable Dual-Pane UI:** Optimized for the Google Pixel 10 Pro Fold with swipe audit queue on cover screen and 8" lineage canvas.
- **USB-C OTG Field Ingest Hub:** Direct SD card reader ingest while traveling.

---

## Installation & Sideloading Guides

### Android Installation

#### Method A: Automated Updates via Obtainium (Recommended)
[Obtainium](https://github.com/ImranR98/Obtainium) tracks GitHub Releases directly and provides one-tap automated background updates.

1. Install [Obtainium](https://github.com/ImranR98/Obtainium) on your Android device.
2. In Obtainium, tap **Add App** (+).
3. Enter the repository URL:
   ```
   https://github.com/s3ntin3l8/branchdam-mobile
   ```
4. (Optional) Under Filter Regular Expression, enter `branchdam-mobile\.apk`.
5. Tap **Add**. Obtainium will automatically download the latest release `.apk` and notify you when new releases are published.

#### Method B: Direct Standalone APK Download
1. Navigate to the [Releases page](https://github.com/s3ntin3l8/branchdam-mobile/releases).
2. Download the latest `branchdam-mobile.apk` asset.
3. Open the downloaded APK on your device.
4. When prompted by Android, enable **Install unknown apps** for your browser or file manager, then tap **Install**.

#### Method C: Build from Source via Gradle
Prerequisites: JDK 21, Android SDK Platform 35, Android NDK (for native Go JNI library), and Go 1.25+.

```bash
# Clone repository
git clone https://github.com/s3ntin3l8/branchdam-mobile.git
cd branchdam-mobile

# 1. Build the gomobile-bound Go engine as an Android AAR
./scripts/build-mobile.sh --android-only
# Produces: android/app/libs/branchdam.aar (ABIs: armeabi-v7a, arm64-v8a, x86, x86_64)

# 2. Assemble release APK and App Bundle (AAB)
cd android
./gradlew assembleRelease bundleRelease

# 3. Install directly to connected device via adb
adb install -r app/build/outputs/apk/release/app-release.apk
```

The legacy CGO recipe (cross-compile `core/bindings` to `libbranchdamcore.so`) is no longer the supported build path. Use the gomobile flow above. The AAR is automatically wired into `android/app/build.gradle.kts` via a file-tree dependency, conditional on the file's presence so the project still builds before the AAR is produced (e.g. for unit-test runs on CI without NDK).

---

### Mobile Library Build (gomobile)

The branchdam engine ships as a single gomobile-bound Go package at the repository root (`branchdam.go`, package name `branchdam`). gomobile produces two artifacts from it:

| Target | Output | Consumer |
|---|---|---|
| Android | `android/app/libs/branchdam.aar` | Kotlin: `io.branchdam.core.Engine` |
| iOS | `ios/Frameworks/branchdam.xcframework` | Swift: `import branchdam` |

To build both at once:

```bash
make mobile-build
# or, individually:
make mobile-build-android   # AAR (requires ANDROID_HOME + ANDROID_NDK_HOME + javac)
make mobile-build-ios       # xcframework (requires macOS + Xcode)
```

`scripts/build-mobile.sh` is the single source of truth. It auto-installs `gomobile` + `gobind` into `$GOPATH/bin` on first run, asserts the required toolchain is available for the target you ask for, and prints the output path on success.

CI runs `make mobile-build` for the target platform as part of `ci.yml` (the `test-android` and `test-ios` jobs build the artifact before running the shell's unit tests). An optional `.github/workflows/mobile-bind.yml` workflow builds both targets and uploads the artifacts; opt in by adding the `mobile-bind` label to a PR.

---

### iOS Sideloading (Personal Apple ID)

The native iOS companion is structured for zero-configuration sideloading using Xcode with any free personal Apple ID.

#### Method A: Xcode Direct Sideloading (Free Apple ID)
1. **Prerequisites**: macOS with Xcode 16+ and an iPhone running iOS 17+.
2. **Clone & Open Project**:
   ```bash
   git clone https://github.com/s3ntin3l8/branchdam-mobile.git
   cd branchdam-mobile/ios
   open BranchDamApp.xcodeproj
   ```
3. **Configure Signing**:
   - In the Xcode Project Navigator, select **BranchDamApp**.
   - Under the **Signing & Capabilities** tab, verify **Automatically manage signing** is enabled.
   - In the **Team** dropdown, select your personal Apple ID team (e.g. `Your Name (Personal Team)`). If you haven't added your Apple ID, click *Add Account...* and sign in with your standard Apple ID.
   - Set a unique Bundle Identifier if needed (e.g., `com.yourname.branchdam-mobile`).
4. **Build & Run**:
   - Connect your iPhone via USB or Wi-Fi.
   - Select your iPhone in the Scheme destination toolbar.
   - Press **Cmd + R** (or click the Play button) to build and deploy.
5. **Trust Developer Certificate on iPhone**:
   - On your iPhone, navigate to **Settings > General > VPN & Device Management**.
   - Tap your developer Apple ID under **Developer App**.
   - Tap **Trust "[Your Apple ID]"** and confirm.
   - Launch `branchDAM` from your home screen.

---

## Server Pairing & Configuration

### 1. QR Code Pairing
To pair the mobile app with your branchDAM server:
1. Open the branchDAM Web UI on your workstation.
2. Go to **Settings > Companion Pairing** and generate a Pairing QR code.
3. In the mobile app, tap **Scan Pairing QR** or point the camera at the code.
4. The QR code encodes a secure `branchdam://` payload:
   ```
   branchdam://?server=http://192.168.1.100:8080&key=<API_KEY>&agent=pixel-10-fold
   ```

### 2. Ingest Architecture & Protocol
- **Handshake (`POST /api/v1/agent/handshake`)**:
  Exchanges device capabilities, checks protocol version, and receives the server's canonical naming template (e.g. `{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}`).
- **Direct Upload (`POST /api/v1/agent/upload`)**:
  Streams chunked binary asset data directly with headers:
  - `User-Agent`: Companion identifier (`branchdam-mobile/<agent_id>`).
  - `X-Filename`: Original camera roll file name.
  - `X-Camera-Model`: Hardware camera model string (e.g. `Google Pixel 10 Pro Fold`, `iPhone 16 Pro Max`).
  - `X-Blake3-Hash`: BLAKE3 checksum for integrity and archive verification.
  - `X-Fast-Hash`: Streaming fast hash (xxHash) for duplicate detection.
  - `X-Capture-Timestamp`: Capture time in Unix epoch seconds.
  - `X-API-Key` / `Authorization`: Companion authentication credential.
- **Safe Space Deletion Model**:
  Assets are only marked eligible for local deletion after the server responds with HTTP `200 OK` / `201 Created` and the BLAKE3 checksum is verified on the master storage tier.

---

## Development & Testing

Run all unit tests, linters, and secret detection:

```bash
make check
```

This runs:
- `pre-commit run --all-files` (detect-secrets, formatters, YAML/JSON checks)
- `go vet ./...` across core modules
- `go test -race -v ./...` with race detector enabled

---

## License

MIT
