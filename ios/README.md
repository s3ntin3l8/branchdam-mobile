# iOS App Module

Native iOS companion module for branchDAM built with SwiftUI, Swift Concurrency, and PhotoKit.

## Architecture

- **UI Layer:** SwiftUI views with dual-pane support and safe space reclaim audit queue.
- **Background Ingest:** `BGAppRefreshTask` and `PHPhotoLibraryChangeObserver` tracking camera roll mutations.
- **Native Core:** C-archive bridge (`BranchDamCoreBridge.swift`) linking `BranchDamCoreHeader.h` and `libbranchdamcore.a`.

## Sideloading with Personal Apple ID

1. Open `ios/BranchDamApp.xcodeproj` in Xcode 16+.
2. Select your personal Apple ID team under **Signing & Capabilities**.
3. Select your connected iPhone and press **Cmd + R**.
4. Trust your certificate in **Settings > General > VPN & Device Management**.
