# iOS App Module

Native iOS companion module for branchDAM built with SwiftUI, Swift Concurrency, and PhotoKit.

## Architecture

- **UI Layer:** SwiftUI views with dual-pane support and safe space reclaim audit queue.
- **Background Ingest:** `BGAppRefreshTask` and `PHPhotoLibraryChangeObserver` tracking camera roll mutations.
- **Native Core:** Swift wrapper (`BranchDamCoreBridge.swift`) around the gomobile-bound `branchdam.xcframework` produced by `make mobile-build-ios`. Sub-issue A wires the bridge; sub-issue B fleshes out the engine API.

## Sideloading with Personal Apple ID

1. Open `ios/BranchDamApp.xcodeproj` in Xcode 16+.
2. Select your personal Apple ID team under **Signing & Capabilities**.
3. Select your connected iPhone and press **Cmd + R**.
4. Trust your certificate in **Settings > General > VPN & Device Management**.
