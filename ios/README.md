# iOS App Module

Native iOS companion module for branchDAM built with SwiftUI, Swift Concurrency, and PhotoKit.

## Architecture

- **UI Layer:** SwiftUI views with dual-pane support and safe space reclaim audit queue.
- **Background Ingest:** `PHPhotoLibraryChangeObserver` tracking camera roll mutations from a persisted position (a PhotoKit persistent change token, falling back to a creation-date cursor), with new assets staged in the core's `pending_import` table before the position advances. Additions become imports (or a confirmation notification whose actions reach `ImportNotificationDelegate`); removals become delete events.
- **Background Sync:** a `BGProcessingTask` (`com.branchdam.mobile.sync`), scheduled when the app enters the background and after pairing, and rescheduled by each run. Uploads are streamed by the Go core's own HTTP client; there is no `URLSession` background uploader.
- **Native Core:** Swift wrapper (`BranchDamCoreBridge.swift`) around the gomobile-bound `branchdam.xcframework` produced by `make mobile-build-ios`. Sub-issue A wires the bridge; sub-issue B fleshes out the engine API.

## Sideloading with Personal Apple ID

1. Build the native core framework locally once on fresh clones: `make mobile-build-ios`.
2. Open `ios/BranchDamApp.xcodeproj` in Xcode 16+.
3. Select your personal Apple ID team under **Signing & Capabilities**.
4. Select your connected iPhone and press **Cmd + R**.
5. Trust your certificate in **Settings > General > VPN & Device Management**.
