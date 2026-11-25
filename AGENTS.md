# AGENTS.md — branchdam-mobile

Mobile companion app for branchDAM. Shared Go core (BLAKE3/FastHash, SQLite
queue, streaming uploader) with Kotlin (Android, Jetpack Compose) and Swift (iOS,
SwiftUI) shells. Handles camera roll ingestion, lineage pairing, and safe
storage reclaim.

This file holds this repo's workflow rules, load-bearing invariants and repo-specific facts. `CLAUDE.md` is a one-line `@AGENTS.md` import, so every agent CLI (Claude Code, Codex, opencode, agy) reads it. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the human contributor workflow and screenshot pipeline.

<!-- mullion:briefing:start -->
This repo's non-obvious correctness invariants are in `.claude/skills/branchdam-mobile/SKILL.md`: safe-reclaim gating, upload integrity, queue.db durability, lineage confidence, the gomobile surface, key rotation and release mechanics. Read it before touching any of those areas. For the self-review pass, or when reviewing a delegated diff, use the reviewer subagent at `.claude/agents/branchdam-mobile-reviewer.md`. It applies that skill as its checklist.

## Workflow Conventions

- Never commit directly to the default branch. Always branch and open a PR.
- Branch off the latest remote default branch, never off your local one: `git fetch origin && git checkout -b <branch> origin/<default>`. A local default branch is routinely stale, which is what makes a PR show up as "out-of-date with the base branch" the moment it's opened.
- Work in a dedicated worktree per branch, under `.wt/<name>` (the branch name with `/` replaced by `-`, so the directory stays flat), off the latest remote default branch: `git fetch origin && git worktree add .wt/<name> -b <branch> origin/<default>`. A fresh worktree does not inherit installed dependencies — reinstall them there before testing or building.
- Commit and PR titles need a Conventional Commits prefix (`feat:`, `fix:`, `chore:`, ...).
- Squash-merge PRs — the PR title becomes the commit message on the default branch.
- Require green CI before merging.
- Run a self-review pass on your own diff before declaring done.

  Request an automated/bot review through the review relay (`mullion review`, or the `request_review` tool) — once after opening the pull request, and again per head commit after pushing fixes for a changes-requested review, then wait for it. A review comment that mentions the review bot is for humans, not agents; if no relay is set up, tell the human instead. One review per pull request at a time: a review that is already running is not re-requested — the request is refused with its run id, and that refusal is the answer, not an error to retry: wait for it, or stop it with stop_review. A review takes 10-30 minutes; check review_status once instead of asking again. After pushing fixes for a changes-requested review, ask again only when no review is in flight. Once the review bot has approved, apply its remaining suggestions and push without requesting another review, even though the head moved: small targeted fixes do not change the verdict, and re-reviewing them only breeds more nits. Never re-trigger a review with an @mention of the review bot: that path is for humans, and it queues another round behind the running one.
- Fixing the code is not enough to address review feedback — reply to each inline comment via the GitHub API, then resolve the thread via the GraphQL `resolveReviewThread` mutation, rather than pushing a silent fix. Reply with `gh api repos/{owner}/{repo}/pulls/<pr>/comments/<comment_id>/replies -f body="…"`; resolve with `gh api graphql -f query='mutation { resolveReviewThread(input: {threadId: "<thread_id>"}) { thread { isResolved } } }'`, taking the thread id from a `reviewThreads` query on the pull request, not from the REST comment id.
- File a tracked issue for anything a plan defers, blocks, or descopes, and link it from the PR (using the repository's issue template if it has one) — a footnote in a plan doc is not a durable record. Write each one as a spec an agent can implement on its own (scope, acceptance criteria), not as a note. Title it the way this repo titles commits and PRs, reusing its existing scope names rather than coining new ones; attach it as a sub-issue to the matching open epic when one exists; and set GitHub's `blocked_by` dependency where one issue cannot start before another closes.
- After a merge, delete the local and remote branch and remove any associated worktree.
- Before pushing, run the full lint/typecheck/test/format gate.
<!-- mullion:briefing:end -->

## Repo specifics

- `make check` is the pre-push gate. It runs `make lint` (the `pre-commit` hooks: detect-secrets, shellcheck, gofmt, go vet and `go mod tidy` for both modules, plus the libc-overlay check) and then `make test`. `pre-commit` must be installed. On macOS `make test` also runs the iOS XCTests. On Linux it skips them.
- There are two Go modules: the root package `branchdam` (the gomobile-bound API: `branchdam.go`, `binding.go`, `keyrotation.go`) and `core/` (client, engine, hasher, queue). Tests, vet and tidy run in both.
- Android unit tests are not part of `make check`. Run `make android-test` when you touch `android/`.
- The gomobile artifacts (`android/app/libs/branchdam.aar`, `ios/Frameworks/branchdam.xcframework`) are gitignored, so a fresh worktree has neither. `make mobile-build` (or `-android` / `-ios`) builds them and needs an NDK. `make ios-test` builds the xcframework itself when it is missing or stale.
- **PR titles / commit subjects:** release-please parses the squash-merge subject on `main`. An unprefixed subject, including a bracketed task-ID prefix like `[T2-7]`, is silently skipped with no error (see `CHANGELOG.md` `## [0.5.0]`). When a PR has exactly one commit, GitHub uses that commit's subject, not the PR title.
- release-please bumps `version` in `branchdam.go` and `CFBundleShortVersionString` in `ios/BranchDAM/Info.plist`. Root tests fail if either drifts from `.release-please-manifest.json`, so never bump them by hand.
- The review bot is Hermes (`s3ntin3l8-hermes[bot]`, `.github/workflows/hermes.yml`). It auto-reviews every non-draft same-repo PR on open, except `mullion/task-*` branches (Task Master runs its own review there, `hermes.yml`); request reviews as the Workflow Conventions describe. Every inline thread must be replied to and resolved before merge.
- `core/client/testdata/contract/` is synced from a branchDAM checkout with `make contract-sync BRANCHDAM=<checkout>`. If detect-secrets then flags a fixture, refresh it with `detect-secrets scan --baseline .secrets.baseline`.
- A sqlite or modernc libc bump requires `scripts/update-libc-overlay.sh <version>`. `make lint` fails while the `third_party/` overlay lags `go.mod`.
- Read `CONTRIBUTING.md` ("Regenerating Screenshots") before running `make screenshots` or `make screenshots-landing`, which need an emulator and a sibling `../branchDAM`.

## Commands

```sh
make check                # lint (pre-commit, both Go modules) + Go tests; iOS XCTests only on macOS; no Android
make test                 # Go tests, both modules
make lint                 # pre-commit hooks + libc-overlay check
make android-test         # Android unit tests + release Kotlin compile (needs the AAR)
make android              # Android debug APK
make mobile-build         # gomobile AAR + xcframework (-android / -ios for one; NDK / macOS)
make ios-test             # iOS XCTests (macOS only); builds the xcframework if missing or stale
make contract-sync BRANCHDAM=<checkout>   # refresh core/client/testdata/contract from branchDAM
```

## Platform folders and cross-boundary changes

`android/AGENTS.md` and `ios/AGENTS.md` hold the shell-specific rules (build prerequisites, reclaim delete path, key
store, strings, background work). Claude Code and Codex load them when you work in that folder; read the other one
too when a change touches both shells.

A change to the gomobile surface (a `Binding*` signature, an `Error.Code`, a baseline field, a `KeyStore` method)
breaks the shells silently, because the Go tests pass on their own. Do it as one change:

1. Change Go (root package and/or `core/`) with its tests.
2. Rebuild the bindings: `make mobile-build-android` (and `-ios` on macOS).
3. Update **both** callers, Kotlin under `android/` and Swift under `ios/`, and their tests.
4. Run `make check` and `make android-test`. On Linux, Swift is unverified locally: say so in the PR and rely on CI's
   "Mobile CI (Android & iOS)" check.

## Architecture & Responsibilities

| Subsystem | Responsibility |
|---|---|
| `core/` | Shared Go engine (BLAKE3/FastHash, offline SQLite `queue.db`, HTTP streaming uploader with a fixed Content-Length, REST client) |
| `android/` | Native Android application (Kotlin, Jetpack Compose, MediaStore observers, WorkManager, Pixel Fold dual-pane UI) |
| `ios/` | Native iOS application (Swift, SwiftUI, PhotoKit, `BGProcessingTask` background sync; uploads run in the Go core) |

## Key Invariants

1. **Offline Queue Persistence (`queue.db`):** All ingest intent, hashes, and events are recorded in local SQLite before network dispatch.
2. **Cryptographic Checksum Verification:** Full hashes are computed using BLAKE3-256 (64 hex characters) before upload, sent in `X-Blake3-Hash` with a single streamed body of fixed `Content-Length`, and the server's returned `blake3Hash` must match.
3. **Safe Storage Reclaim:** a local original is deleted only when `node-status` reports the node found, `verified`, on `TIER3_MASTER_ARCHIVE`, live (`ACTIVE`/`HIDDEN`), with a `fullHash` equal to the BLAKE3 in `queue.db` (gates checked in that order; first failure is the reason shown), **and** the item's current size/mtime still match the baseline recorded at enqueue. Any missing hash or baseline keeps the file; every race fails toward keeping (#217, #229). Gate order, error mapping and shell rules: skill §1.
4. **Lineage Precision:** DNG RAW + Ultra HDR JPEG pairs and Motion Photos emit `EVENT_EDGE_ATTACHED` with `confidence: 1.00`, automatically. Anything below 1.00 (timestamp-proximity pairs, in-phone edits) is never auto-emitted: the core answers `REVIEW_REQUIRED`, the pair waits in the lineage review screen, and the user's confirm or reject is stored in `queue.db` (`lineage_review`). A confirmed edge keeps its original confidence.

## Rotated API keys: store, then switch, fall back once

`core/client/keyrotation.go` opens `pendingRotation.sealedApiKey` with the key the handshake used (a value that does not open is ignored; a cleartext `apiKey` is never used), hands it to the shell's `KeyStore` (`BindingSetKeyStore`: Android `EngineKeyStore` -> `RotatedApiKeyStore`, iOS `KeychainRotatedKeyStore` -> `PairingCredentialStore.saveRotatedAPIKey`), and switches the in-memory key only after that durable write returns without error, then adopts it with a confirming handshake. A store must throw unless the key is really written (Android `commit()` and read-back, never `apply()`; no plaintext fallback). A `401` on the new key's first use reverts to the old key, retries that request once, stores the old key again once accepted, and never re-adopts that key id in the process. Every request reads the key once and signs with that same value. A `pendingRotation` with an empty `sealedApiKey` (server without `BRANCHDAM_SECRET_KEY`) cannot be adopted: the client keeps its `previousKeyExpiresAtUnix` as the re-pair deadline (`Client.RepairDeadlineUnix` -> `BindingRepairDeadlineUnix`, in memory, guarded by `keys.mu`), cleared by a handshake response without `pendingRotation` or by a successful adoption, and the Settings screens (Android `SettingsViewModel.repairDeadlineUnix`, iOS `QrPairingView`) show "Re-pair this device before <date>" while it is in the future.
