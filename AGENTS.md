# AGENTS.md — branchdam-mobile

<!-- mullion:briefing:start -->

Mobile companion app for branchDAM. Shared Go core (BLAKE3/FastHash, SQLite
queue, chunked uploader) with Kotlin (Android, Jetpack Compose) and Swift (iOS,
SwiftUI) shells. Handles camera roll ingestion, lineage pairing, and safe
storage reclaim.

Key commands: `make check` (lint + test + android), `make test` (Go core only),
`make android` (debug build).

Critical invariants: offline queue persistence, safe reclaim (verified + tier
check), lineage precision (confidence values). Full details in `CLAUDE.md`.

<!-- mullion:briefing:end -->
