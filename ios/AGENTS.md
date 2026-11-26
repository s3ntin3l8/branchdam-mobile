# AGENTS.md — ios/

iOS shell (Swift, SwiftUI, PhotoKit, `BGProcessingTask`) over the gomobile-bound Go core. Read with the root
[`AGENTS.md`](../AGENTS.md) and `.claude/skills/branchdam-mobile/SKILL.md`; this file adds only what is specific to
`ios/`.

## Build and test

- **macOS only.** `make ios-test` (XCTests on a simulator) and `make mobile-build-ios` (`Frameworks/branchdam.xcframework`,
  gitignored) need Xcode 16+. On Linux, Swift changes cannot be compiled or tested locally: say so in the PR and rely on
  CI's "Mobile CI (Android & iOS)" check and `mobile-bind.yml`'s macOS xcframework job.
- `make check` on Linux still runs the root Go tests that read `ios/`: `Info.plist` version
  (`TestIOSBundleVersionMatchesReleaseManifest`), `Localizable.strings` and `.stringsdict` well-formedness.
- Never bump `CFBundleShortVersionString` by hand; release-please owns it.

## Rules

- **Reclaim deletes** go only through `Triage/AppleSafeSpaceManager.reclaimSafeSpace` or `UI/GalleryView.batchReclaim`,
  both via `BranchDamCoreBridge.reclaimSafeSpace` → `PHAssetChangeRequest.deleteAssets`, and only on `Eligible=true`
  (skill §1). Reset `is_offloaded` to `false` for every approved asset that wasn't deleted. PhotoKit has no file size, so size is
  always passed as 0; the timestamp is `PHAsset.modificationDate`, or `creationDate` when that is nil (#232). Read the
  baseline before staging bytes for enqueue, or drop it if the asset changed after staging.
- **Rotated keys:** `KeychainRotatedKeyStore` (`Core/BranchDamCoreBridge.swift`) → `PairingCredentialStore.saveRotatedAPIKey`
  (`Core/AppleKeychain.swift`) must throw unless the Keychain write really happened. It runs on a Go thread; never
  block on the engine's own queue. `QrPairingView` shows the "Re-pair this device before <date>" deadline.
- **Errors:** `coreErrorCode(_:)` (`Core/BranchDamCoreBridge.swift`) parses the error code from `branchdam: <CODE>: ...`
  messages (skill §5) with the same pattern and meanings as Android; never infer anything from other message text.
  `syncBatchOutcome` returns `SyncBatchOutcome` (`.success` / `.failed(code:)`); `BackgroundSyncManager.recordOutcome`
  writes `BranchDamKeys.syncAuthFailedAt` on `AUTH_FAILED` and clears it on `.success`, `QrPairingView.handlePostConnect`
  also clears it after a successful re-pair (mirroring Android's connect-triggered immediate sync), and `SyncStatusViewModel`
  surfaces `needsRepair` as a "Re-pair this device" prompt linking to Settings (`QrPairingView`).
- **Local IDs** are the bare `PHAsset.localIdentifier` (no `ph://`); a paired video child is `<id>/pairedVideo`.
- **Background sync** is a `BGProcessingTask` (`com.branchdam.mobile.sync`) scheduled on entering the background and
  after pairing, and rescheduled when a run completes or expires (#305); any new exit path must call `scheduleBackgroundSync()`. Uploads run in the Go core's HTTP client; don't add a `URLSession`
  background uploader.
- **Staged media** lives in `Caches/staged_media` (`PhotoKitObserver.stagedMediaDirectory`), which the OS may purge
  before an upload runs (#309); keep that in mind before adding anything that relies on a staged file surviving.
- **Camera-roll observation** advances its persisted position (PhotoKit change token, else a creation-date cursor)
  only after new assets are staged in the core's `pending_import` table.

## First run

`BranchDAMApp` shows `WelcomeView` only until onboarding is done: `OnboardingState` (`BranchDamKeys.onboardingCompleted`,
written when access is granted or **Set Up Later** is tapped) or an already `.authorized`/`.limited` photo status skips
it. On `.denied`/`.restricted` the Welcome screen shows the banner's wording and an **Open Settings** button instead of
the grant button. The banner's Grant action reports the re-read status to `ContentView` so the banner clears at once.
