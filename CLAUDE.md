# CLAUDE.md — branchdam-mobile

Guidance for Claude Code (claude.ai/code) and AI coding assistants working in `branchdam-mobile`.

## Guidelines

- **PR & Issue Templates:** Fill [`.github/pull_request_template.md`](.github/pull_request_template.md) and [`.github/ISSUE_TEMPLATE/issue-blueprint.md`](.github/ISSUE_TEMPLATE/issue-blueprint.md). See [`CONTRIBUTING.md`](CONTRIBUTING.md) for branch protection rules and checklist.
- **Review Thread Resolution:** Hermes/human review threads require two API calls to resolve:
  ```sh
  # 1. Reply to inline comment (REST)
  gh api repos/s3ntin3l8/branchdam-mobile/pulls/<PR>/comments/<comment_id>/replies -f body="Fixed in <sha>"
  # 2. Resolve thread (GraphQL)
  gh api graphql -f query="mutation { resolveReviewThread(input: {threadId: \"<thread_id>\"}) { thread { isResolved } } }"
  ```

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
