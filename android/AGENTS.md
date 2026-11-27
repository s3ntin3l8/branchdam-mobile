# AGENTS.md — android/

Android shell (Kotlin, Jetpack Compose, WorkManager) over the gomobile-bound Go core. Read with the root
[`AGENTS.md`](../AGENTS.md) and `.claude/skills/branchdam-mobile/SKILL.md`; this file adds only what is specific to
`android/`.

## Build and test

- A fresh worktree has no `app/libs/branchdam.aar` (gitignored), and `EngineHolder` imports
  `io.branchdam.core.branchdam.Branchdam`, so Kotlin won't compile without it. Run `make mobile-build-android` (needs
  an NDK under `$ANDROID_HOME/ndk`) first, and again after any change to the root Go package or `core/`.
- `make android-test` (`testDebugUnitTest` + `compileReleaseKotlin`) is the Android gate; `make check` does not run
  it. `make android` assembles a debug APK.
- CI additionally checks 16 KB ELF alignment of the `.so` files inside the AAR (`verify-elf-alignment`). The NDK
  version (`25.1.8937393`) is pinned in `ci.yml`, `mobile-bind.yml` and `release-please.yml`; change all of them together.

## Rules

- **Reclaim deletes** go only through `triage/SafeSpaceManager.reclaimWithCurrentState` → `EngineHolder.reclaimSafeSpace`
  → the MediaStore delete, and only on `Eligible=true` (skill §1). The current size/mtime passed in are MediaStore
  `SIZE`/`DATE_MODIFIED` (`triage/LocalFileState.kt`). Read the baseline before staging bytes for enqueue. The
  gallery's user-initiated delete is a separate flow; never reuse it for reclaim.
- **Rotated keys:** `EngineKeyStore` → `RotatedApiKeyStore` must write with `commit()` and read the value back, never
  `apply()`, with no plaintext fallback. `StoreAPIKey` is called on a Go thread and must not block on the engine
  executor (deadlock). Credentials live in EncryptedSharedPreferences; `PrefKeyMigration` moves plain prefs into it.
- **Errors:** gomobile hands Kotlin only the message, so the code is parsed from `branchdam: <CODE>: ...` by
  `EngineHolder.coreErrorCode` (skill §5). `DB_ERROR`/`INTERNAL` are transient (retry), `AUTH_FAILED` means re-pair,
  `VERIFIED_REQUIRED` is an ineligible verdict. Don't match on any other part of the message.
- **`EngineHolder` mocks only when `!nativeAvailable`.** When the native library is loaded but the engine isn't open
  yet, return the failure value (0 / `""` / `false`), as `enqueueMedia` does. Returning a fake UUID or `true` makes
  callers (e.g. `TrashSyncObserver`) mark the item processed and the event is lost. (Three methods still do this: #304.)
- **Local IDs** are `ContentUris.withAppendedId(<collection the row came from>, id)` (Images or Video); a motion
  photo's video child is `<stillLocalId>/microvideo` (`<uri>/microvideo` for MediaStore items,
  `<uri>#<size>-<mtime>/microvideo` for OTG items). Trash, lineage, reclaim and status all join on them.
- **Staged files must survive a restart:** the engine reopens the enqueued path at sync time, possibly after process
  death. Enqueue a real file under `noBackupFilesDir` (`UploadStaging`, `OtgStaging`), never a content URI, a
  `/proc/self/fd` path or `cacheDir`. Motion-photo videos use `UploadStaging` (#308) or, for OTG, `OtgStaging`
  extracted from the verified copy. Delete a staged file referenced by an upload row only once the row is
  COMPLETED/OFFLOADED.
- **Upload source:** when the original can't be staged, `UploadStaging.pathForUpload` falls back to a location-redacted
  copy. Such an upload must not carry a reclaim baseline (skill §1; not yet enforced, #303).
- **Camera-roll cursor** (`NewMediaIntake`): advance only past items whose upload row or `pending_import` row is
  persisted; stop at the first failure. Import-notification actions apply only to rows staged before
  `stagedBeforeUnix` and announced, and run as a unique WorkManager chain, not inside the broadcast receiver.
- **Sync scheduling:** every sync goes through `service/SyncScheduler` (periodic syncs are always UNMETERED; immediate
  ones follow the mobile-data setting; "Sync now" once bypassed this). `SyncWorker` must rethrow
  `CancellationException` and run the engine through `runCancellingEngine`, so losing the Wi-Fi constraint stops the
  Go upload.
- **Deletes:** only the gallery's user delete queues `EVENT_NODE_DELETED` (it trashes the server master); reclaim
  never does. After a reclaim, reset `is_offloaded` to `false` for every approved item that wasn't deleted.
- **Strings:** all `values*/strings*.xml` merge into one table, so a name must be unique across files, and a string
  with more than one format argument must use positional forms (`%1$s`). `TestAndroidStringResourcesAreWellFormed`
  (root `repo_resources_test.go`, run by `make check`) enforces this. A new Compose screen goes into
  `HardcodedUiStringsTest`'s `extractedScreens`.
- **Lineage:** only `confidence: 1.00` pairs are sent automatically; anything lower waits in the lineage review
  screen (`lineage/LineageReview.kt`, root invariant 4).
