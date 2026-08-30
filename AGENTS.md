# AGENTS.md — branchdam-mobile

<!-- mullion:briefing:start -->

Mobile companion app for branchDAM. Shared Go core (BLAKE3/FastHash, SQLite
queue, chunked uploader) with Kotlin (Android, Jetpack Compose) and Swift (iOS,
SwiftUI) shells. Handles camera roll ingestion, lineage pairing, and safe
storage reclaim.

Key commands: `make check` (lint + core tests), `make test` (Go core only),
`make android` (debug build).

Critical invariants: offline queue persistence, safe reclaim (verified + tier
check), lineage precision (confidence values). Full details in `CLAUDE.md`.

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
