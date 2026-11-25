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
  version is pinned in `ci.yml` (`25.1.8937393`); change it there and locally together.

## Rules

- **Reclaim deletes** go only through `triage/SafeSpaceManager.reclaimWithCurrentState` → `EngineHolder.reclaimSafeSpace`
  → the MediaStore delete, and only on `Eligible=true` (skill §1). The current size/mtime passed in are MediaStore
  `SIZE`/`DATE_MODIFIED` (`triage/LocalFileState.kt`). Read the baseline before staging bytes for enqueue. The
  gallery's user-initiated delete is a separate flow; never reuse it for reclaim.
- **Rotated keys:** `EngineKeyStore` → `RotatedApiKeyStore` must write with `commit()` and read the value back, never
  `apply()`, with no plaintext fallback. `StoreAPIKey` is called on a Go thread and must not block on the engine
  executor (deadlock). Credentials live in EncryptedSharedPreferences; `PrefKeyMigration` moves plain prefs into it.
- **Errors:** switch on the binding's `Error.Code`, never on the message. `DB_ERROR`/`INTERNAL` are transient
  (retry), `AUTH_FAILED` means re-pair, `VERIFIED_REQUIRED` is an ineligible verdict.
- **Strings:** all `values*/strings*.xml` merge into one table, so a name must be unique across files, and a string
  with more than one format argument must use positional forms (`%1$s`). `TestAndroidStringResourcesAreWellFormed`
  (root `repo_resources_test.go`, run by `make check`) enforces this.
- **Lineage:** only `confidence: 1.00` pairs are sent automatically; anything lower waits in the lineage review
  screen (`lineage/LineageReview.kt`, root invariant 4).
