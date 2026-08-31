# AGENTS.md — branchdam-mobile

<!-- mullion:briefing:start -->

Mobile companion app for branchDAM. Shared Go core (BLAKE3/FastHash, SQLite
queue, chunked uploader) with Kotlin (Android, Jetpack Compose) and Swift (iOS,
SwiftUI) shells. Handles camera roll ingestion, lineage pairing, and safe
storage reclaim.

## Commands

```sh
# Pre-PR Gates & Testing
make check           # Run lint + core unit tests + android checks
make test            # Run go core unit tests
make lint            # pre-commit check + golangci-lint
make android         # Assemble Android debug build
```

## Architecture & Responsibilities

| Subsystem | Responsibility |
|---|---|
| `core/` | Shared Go engine (BLAKE3/FastHash, offline SQLite `queue.db`, HTTP chunked uploader, REST client) |
| `android/` | Native Android application (Kotlin, Jetpack Compose, MediaStore observers, WorkManager, Pixel Fold dual-pane UI) |
| `ios/` | Native iOS application (Swift, SwiftUI, PhotoKit, NSURLSession background uploader) |

## Key Invariants

1. **Offline Queue Persistence (`queue.db`):** All ingest intent, hashes, and events are recorded in local SQLite before network dispatch.
2. **Cryptographic Checksum Verification:** Full hashes are computed using BLAKE3-256 (64 hex characters) and streamed during chunked upload.
3. **Safe Storage Reclaim:** Local media files are NEVER deleted for storage reclaim unless `POST /api/v1/agent/node-status` reports `verified: true`.
4. **Lineage Precision:** DNG RAW + Ultra HDR JPEG pairs and Motion Photos emit `EVENT_EDGE_ATTACHED` with `confidence: 1.00`.

## Review thread resolution

Every review thread (Hermes or human) must be replied to and resolved before
a PR is mergeable. This is a GraphQL-only concept, not a `gh pr` verb:

```sh
# 1. Reply to inline comment (REST)
gh api repos/s3ntin3l8/branchdam-mobile/pulls/<PR>/comments/<comment_id>/replies -f body="Fixed in <sha>"
# 2. Resolve thread (GraphQL)
gh api graphql -f query="mutation { resolveReviewThread(input: {threadId: \"<thread_id>\"}) { thread { isResolved } } }"
```

<!-- mullion:briefing:end -->
