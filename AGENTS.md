# AGENTS.md — branchdam-mobile

This file is the single source of truth for this repo's workflow rules and
load-bearing invariants — the ones every agent needs before touching
anything, regardless of which CLI you are. `CLAUDE.md` is a one-line
`@AGENTS.md` import, so every CLI (Claude Code, Codex, opencode, agy) reads
this file, natively or via that import. See
[`CONTRIBUTING.md`](CONTRIBUTING.md) for the contributor workflow.

Mobile companion app for branchDAM. Shared Go core (BLAKE3/FastHash, SQLite
queue, streaming uploader) with Kotlin (Android, Jetpack Compose) and Swift (iOS,
SwiftUI) shells. Handles camera roll ingestion, lineage pairing, and safe
storage reclaim.

## Commands

```sh
# Pre-PR Gates & Testing
make check           # lint + core tests (no Android build)
make test            # Run go core unit tests
make lint            # pre-commit check + go vet
make android         # Assemble Android debug build
```

## Architecture & Responsibilities

| Subsystem | Responsibility |
|---|---|
| `core/` | Shared Go engine (BLAKE3/FastHash, offline SQLite `queue.db`, HTTP streaming uploader with a fixed Content-Length, REST client) |
| `android/` | Native Android application (Kotlin, Jetpack Compose, MediaStore observers, WorkManager, Pixel Fold dual-pane UI) |
| `ios/` | Native iOS application (Swift, SwiftUI, PhotoKit, `BGProcessingTask` background sync; uploads run in the Go core) |

## Key Invariants

1. **Offline Queue Persistence (`queue.db`):** All ingest intent, hashes, and events are recorded in local SQLite before network dispatch.
2. **Cryptographic Checksum Verification:** Full hashes are computed using BLAKE3-256 (64 hex characters) before upload, sent in `X-Blake3-Hash` with a single streamed body of fixed `Content-Length`, and the server's returned `blake3Hash` must match.
3. **Safe Storage Reclaim:** Local media files are NEVER deleted for storage reclaim unless `POST /api/v1/agent/node-status` reports the node found, `verified: true` (a server-computed hash), on `TIER3_MASTER_ARCHIVE` (`core/engine` `ReclaimTier`) and live (`ACTIVE`/`HIDDEN`) -- checked in that order, and the first gate that fails is the reason shown. The client has no hash from node-status to compare against yet (#217).
4. **Lineage Precision:** DNG RAW + Ultra HDR JPEG pairs and Motion Photos emit `EVENT_EDGE_ATTACHED` with `confidence: 1.00`, automatically. Anything below 1.00 (timestamp-proximity pairs, in-phone edits) is never auto-emitted: the core answers `REVIEW_REQUIRED`, the pair waits in the lineage review screen, and the user's confirm or reject is stored in `queue.db` (`lineage_review`). A confirmed edge keeps its original confidence.

## Rotated API keys: store, then switch, fall back once

`core/client/keyrotation.go` opens `pendingRotation.sealedApiKey` with the key the handshake used (a value that does not open is ignored; a cleartext `apiKey` is never used), hands it to the shell's `KeyStore` (`BindingSetKeyStore`: Android `EngineKeyStore` -> `RotatedApiKeyStore`, iOS `KeychainRotatedKeyStore` -> `PairingCredentialStore.saveRotatedAPIKey`), and switches the in-memory key only after that durable write returns without error, then adopts it with a confirming handshake. A store must throw unless the key is really written (Android `commit()` and read-back, never `apply()`; no plaintext fallback). A `401` on the new key's first use reverts to the old key, retries that request once, stores the old key again once accepted, and never re-adopts that key id in the process. Every request reads the key once and signs with that same value.

## PR titles must be Conventional Commits

This repo squash-merges PRs (`squash_merge_commit_title: COMMIT_OR_PR_TITLE`),
and [release-please](release-please-config.json) parses the resulting `main`
commit subject to decide whether to cut a release — an unprefixed subject
(including a bracketed task-ID prefix like `[T2-7]`) is silently skipped, with
no error. Use a [Conventional Commits](https://www.conventionalcommits.org/)
prefix (`feat:`, `fix:`, `chore:`, `docs:`, ...) on the **PR title**. If a PR
has more than one commit, GitHub uses the PR title for the squash subject; if
it has exactly one commit, GitHub uses that commit's own subject instead — so
give a single-commit PR's commit message the same prefix, not just the PR
title. See `CHANGELOG.md`'s `## [0.5.0]` entry for the incident this caused.

## Review thread resolution

Hermes reviews automatically on open (`.github/workflows/hermes.yml`'s
`auto-review` job) — don't also `@s3ntin3l8-hermes Review` right after
opening the PR, or you'll trigger a redundant second review. A re-review
can be requested the same way after pushing fixes, but keep it to a
couple of rounds — don't loop on it indefinitely.

Every review thread (Hermes or human) must be replied to and resolved before
a PR is mergeable. This is a GraphQL-only concept, not a `gh pr` verb:

```sh
# 1. Reply to inline comment (REST)
gh api repos/s3ntin3l8/branchdam-mobile/pulls/<PR>/comments/<comment_id>/replies -f body="Fixed in <sha>"
# 2. Resolve thread (GraphQL)
gh api graphql -f query="mutation { resolveReviewThread(input: {threadId: \"<thread_id>\"}) { thread { isResolved } } }"
```
