# Contributing Guide

## Branching & Commit Guidelines

- **Branch off the latest remote default branch, never off your local one:**
  ```sh
  git fetch origin && git checkout -b feat/my-feature origin/main
  ```
- **No direct commits to `main`:** All changes must go through pull requests.
- **One Issue = One PR:** Keep PR scopes aligned strictly with the issue blueprint.
- **PR title needs a Conventional Commits prefix** (`feat:`, `fix:`, `chore:`,
  `docs:`, ...). This repo squash-merges, and release-please parses the
  resulting commit subject on `main` to decide whether to cut a release — an
  unprefixed title (including a bracketed task-ID prefix) is silently
  skipped. See `AGENTS.md`'s "PR titles must be Conventional Commits" for the
  single-commit-PR nuance.

## Automated Reviews with Hermes

This repo runs automated code review via **Hermes**.
- On PR open or ready for review, Hermes reviews changes automatically.
- To request a re-review or triage, comment:
  ```
  @s3ntin3l8-hermes Review
  ```

## Regenerating Screenshots

`docs/screenshots/` holds Android captures of the main screens (lineage
audit queue, gallery, sync status), referenced from the README. They come
from a reproducible pipeline, not from a personal device:

```sh
make screenshots                       # writes docs/screenshots/
make screenshots OUT=/abs/path/shots   # any other directory
```

`scripts/screenshots.sh` builds the debug APK, boots a throwaway copy of
the `pixel_10` AVD headless (only its `config.ini` is copied into a scratch
`ANDROID_AVD_HOME`, so your AVD's data is never touched), starts a branchDAM
demo instance from the sibling `../branchDAM` checkout (`make demo` tooling,
synthetic media, scratch dir, `127.0.0.1:3975`), pairs the app with it,
pushes a few demo photos into `DCIM/Camera`, switches to dark theme with a
demo-mode status bar and captures each screen with `adb exec-out screencap`.
On any exit it kills the emulator and the demo and deletes the scratch dirs.

Requirements: Android SDK at `$ANDROID_HOME` (default `~/Android/Sdk`, adb
and emulator are used from there), a `pixel_10` AVD, an NDK under
`$ANDROID_HOME/ndk` (the Go core is rebuilt every run), KVM, JDK, Go,
node/npm, ffmpeg, exiftool, curl and jq; `../branchDAM` must include the
demo tooling (branchdam#530). Override with `AVD`, `EMU_PORT` (default
5580), `BRANCHDAM_DIR` and `DEMO_PORT`; set `DEBUG_DIR` to keep a screencap,
UI dump, logcat and the build/demo logs when a run fails. The script only
ever addresses its own `emulator-$EMU_PORT` and refuses to run if that
serial is already attached. A full run takes 10-20 minutes, most of it the
demo's build and media scan.

The throwaway emulator boots with `-selinux permissive`. That stops zygote
from applying the per-app seccomp filter, which kills the x86_64 build of the
Go core (raw `lstat` in `modernc.org/libc`, see #211). The arm64 build can't
stand in for it, because it crashes under the image's ARM translation.

Pairing is automated without a camera: the script creates a pairing via
`POST /api/v1/companion/pairings` (with `X-Forwarded-Host: 10.0.2.2:3975`,
the emulator's address for the host) and writes the resulting server, key
and agent ID into the debug app's plain prefs file with `run-as`; the app's
own `PrefKeyMigration` moves them into EncryptedSharedPreferences on start,
the same path the QR flow ends in.

Shell scripts are linted by the `shellcheck` pre-commit hook (`make lint`).
