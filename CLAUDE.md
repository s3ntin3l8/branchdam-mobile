# CLAUDE.md — branchdam-mobile

Guidance for Claude Code (claude.ai/code) and AI coding assistants working in `branchdam-mobile`.

**Before anything else, read [`AGENTS.md`](AGENTS.md).** It has the
non-negotiables for working in this repo.

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
