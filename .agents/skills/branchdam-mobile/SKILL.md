---
name: branchdam-mobile
description: Correctness invariants for branchdam-mobile (Go core + gomobile binding + Kotlin/Swift shells) that look harmless when you read one file but break another part of the codebase. Read this before changing anything in reclaim/delete paths, the uploader, queue.db schema or sync, lineage events, the gomobile Binding* surface, key rotation, or release/version files, and use it as the checklist when reviewing such changes.
---
<!-- mullion:scaffold:branchdam-mobile -->

# branchdam-mobile invariants

Each rule below is enforced in code or tests somewhere. A change that breaks one usually still compiles, and often passes the tests next to the code you touched.

## 1. Safe space reclaim fails closed (core/engine/engine.go, branchdam.go)

- Deleting a local original is allowed only after `Engine.ReclaimSafeSpace` / `BindingReclaimSafeSpace` returns `Eligible=true`. On Android the path is `SafeSpaceManager.reclaimWithCurrentState`, then `EngineHolder.reclaimSafeSpace`, then the MediaStore delete. On iOS it is `AppleSafeSpaceManager.reclaimSafeSpace`, then `BranchDamCoreBridge.reclaimSafeSpace`, then `PHAssetChangeRequest.deleteAssets`. iOS also reclaims from `GalleryView.batchReclaim`, through the same gate. Any new delete path must go through that gate. Deletes the user starts in the gallery are a separate flow and must not be reused for reclaim.
- `reclaimIneligibleReason` checks the gates in this order: found, `Verified`, `Tier == ReclaimTier` (`TIER3_MASTER_ARCHIVE`, the only durable tier), lifecycle `ACTIVE`/`HIDDEN`, server `fullHash` present (`no-server-hash`), local BLAKE3 present (`no-local-hash`), then `strings.EqualFold` equality (`hash-mismatch`). The first gate that fails is the reason the user sees, so do not reorder the gates. `verified: true` on its own never authorises a delete. A missing hash on either side keeps the file. Servers older than branchDAM #538 send no `fullHash`, so reclaim keeps every file there, and that is the intended behaviour.
- `localChangedReason` runs **after** the server gates. Every non-zero baseline field (`local_media_state.size_bytes` / `modified_unix`) must equal what the shell read immediately before the call. If both fields are zero, the result is `no-local-baseline` (keep). iOS always passes size 0 because PhotoKit has no size, and passes `modificationDate`, or `creationDate` when that is nil. The shell must read the baseline **before** it stages the bytes for enqueue, or drop the baseline if the asset changed after staging. Any race must end in keeping the file.
- **`is_offloaded`:** the gate sets it to `true` *before* the shell deletes, and the shells also set it through `BindingSetMediaOffloaded`. Every approved item that was not actually deleted must be set back to `false` (`SafeSpaceManager.completeReclaim`, `AppleSafeSpaceManager`, `GalleryView.batchReclaim`). Never set `true` outside a gated reclaim.
- **Reclaim is safe only if the enqueued bytes are the original.** An upload of a location-redacted or fallback copy (Android `STAGE_REDACTED`, the API 30+ DATA-path fallback, revoked `ACCESS_MEDIA_LOCATION`) must not carry a reclaim baseline, so it is never reclaimed; otherwise every gate passes against the stripped copy and the original's GPS metadata is lost. (Not yet enforced: #303.)
- **Delete events trash the server master.** Only the gallery's user-initiated delete may queue `EVENT_NODE_DELETED`; reclaim never does. `planNodeDeleted` skips offloaded items, including another local ID on the same node (`IsNodeOffloaded`). The shells' `isMediaOffloaded` checks return `false` on error (fail open), so this core check is the load-bearing one; don't weaken it.
- `SafeSpaceReclaim` trusts only the node-status entry whose `NodeUUID` equals the row's node. `CheckSafeSpaceCandidates` checks each localID against its own recorded hash, even when several localIDs share a node.
- Error mapping is a contract with the shells. `ErrLocalReadFailed` and `ErrLocalFlagSetFailed` map to `DB_ERROR`, and NETWORK/IO errors map to `INTERNAL`. Both are transient, and the shell retries. `AUTH_FAILED` means re-pair. Everything else maps to `VERIFIED_REQUIRED`, which is an ineligible verdict. Match these with `errors.Is` / `errors.As` on the sentinels, never on message strings. An unrecognised error must not be treated as eligible.

## 2. Upload integrity (core/client/uploader.go, core/engine/engine.go)

- The upload is one streamed body with `req.ContentLength = sizeBytes` and the `X-Blake3-Hash` header. Never switch it to chunked or unknown length. A wrapper reader that hides the length is a bug.
- The body cannot be replayed. A refused rotated key is **not** retried inside `UploadStream`, and `observeAuth` only reverts the key. Do not add a retry loop that re-reads `r`.
- A response must carry a `blake3Hash`, and that hash must `EqualFold` the one sent. If it is absent, the result is `HASH_MISMATCH`, not a pass. A 409 without a `nodeUuid` is `DEDUP_NO_NODE_UUID` (hard failure), not a soft dedup.
- Every completion path must call **both** `MarkUploadComplete` and `UpdateLocalMediaNodeUUID(blake3, nodeUUID)`. The completion paths are a 2xx success, `X-Dedup` / `DEDUPLICATED`, a 409 `DedupError`, and the `preScreen` content check. Without the node UUID, lineage translation and reclaim cannot resolve the item. If `MarkUploadComplete` fails, call `releaseUploads` on the row. Do not strand it IN_PROGRESS. (The `preScreen` path currently ignores that error; don't copy it.)
- Before streaming, the engine re-stats the **opened** file. `fileChangedSinceEnqueue` triggers `refreshChangedUpload`, which re-hashes and records the new hashes, so stale hashes are never sent. `hashOpenFile` records the mtime from before the hash. Every hash site, including the re-hash, is bounded by `checkHashSize` / `maxEnqueueFileBytes`.
- A row may be completed from a content check only when check-content reports `found`, which is a full-hash match. Never complete on a fast-hash or `possibleMatch` answer.
- `core/hasher`'s `FastHash` is a byte-for-byte port of branchDAM's, pinned by `TestFastHashGoldenVectors`. Fix the code, never the vectors.
- Every `http.Client` keeps `CheckRedirect: refuseUnsafeRedirect` (`core/client/client.go`); without it `X-API-Key` is replayed to the redirect target.
- Paths arrive from the shells across the FFI boundary. Reject non-regular files both before the open (`os.Stat`) and after it (`file.Stat`), and open with `openFlagsNonblock`. Opening a FIFO blocks and would hold the engine lock for the whole batch.

## 3. queue.db (core/queue)

- Ingest intent, hashes and events are written to queue.db before any network call (invariant 1). Binding calls hold the read lock from `acquireBindingEngine` for their whole duration (`defer release()`). Releasing it early lets `BindingClose` close the DB under an in-flight enqueue, and that enqueue is lost.
- A batch can be interrupted by cancel, the batch deadline, or `AUTH_FAILED`. The claimed rows then go back through `releaseUploads` / `releaseEvents`, which do not count a retry. Use `MarkUploadFailed` / `MarkEventFailed` only for real failed attempts. `Open` resets IN_PROGRESS rows, and `reclaimStaleClaims` runs before every sync.
- `SetMaxOpenConns(1)`. Pragmas go into the DSN's `_pragma` list (`queuePragmas`), never into a later `Exec`, because a later `Exec` applies to only one connection. The DSN is built with `url.URL` so that `?`, `#` and `%` in paths are escaped. It mirrors branchdam-agent.
- A new column goes into **both** `schemaSQL` and `addedColumns`, with a `DEFAULT`. `CREATE TABLE IF NOT EXISTS` never alters an existing install.
- Event payloads are capped at `queue.MaxEventPayloadBytes` (64 KiB). An oversized payload is `INVALID_INPUT` (`ErrPayloadTooLarge`), not `DB_ERROR`. Do not raise the cap or bypass it.

## 4. Events and lineage

- Lineage events are queued with **local IDs** and translated at sync (`planEvent`). If an item is not uploaded yet, the event is deferred without using a retry. Any 4xx other than 401/403/408/429 is terminal for that event. 401/403 stop the batch. Resends reuse `event_uuid`, which keeps them idempotent.
- `EnqueueLineageEvent` accepts only `ConfidenceExact` (1.00). Anything lower returns `REVIEW_REQUIRED` and is never sent automatically. `ConfirmLineageEdge` keeps the resolver's confidence and never raises it to 1.00. Values below the server's 0.50 floor are `INVALID_INPUT`. The decision and its event are written in one transaction, confirming twice returns the first event UUID, and a rejection is persisted in `lineage_review` and never proposed again. `MOTION_PHOTO_CONTAINS` is sent as `DERIVED_FROM`. Validate new relationship types through `engine.ServerRelationshipType`.

## 5. gomobile surface (branchdam.go, binding.go, keyrotation.go)

- gobind drops functions that take or return structs. Shell-facing `Binding*` functions take and return only primitives, strings and `error`. The `Engine` methods may use structs.
- gomobile exposes only the error *message* to Kotlin, so Android regex-parses `branchdam: <CODE>: <message>` (`EngineHolder.coreErrorCode`) and matches reclaim reason tokens such as `reclaim ineligible: local-changed` / `no-local-baseline` (`SafeSpaceManager.isLocalGateReason`). The `branchdam.Error` format (`branchdam.go`) and those reason strings are therefore the wire contract: never reword them. Codes include `INVALID_INPUT`, `DB_ERROR`, `AUTH_FAILED`, `REVIEW_REQUIRED`, `CONTEXT_CANCELED`, ...; renaming or remapping one breaks the shells silently. A 401/403 must surface as `AUTH_FAILED`, never `NETWORK_ERROR`.
- Local IDs are the join key for enqueue, lineage, trash, reclaim and status. Android: `ContentUris.withAppendedId(<collection the row came from>, id)`, synthetic child `<uri>/microvideo`. iOS: the bare `PHAsset.localIdentifier` (no `ph://`; the core accepts `ph://` only as a legacy fallback), synthetic child `<id>/pairedVideo`. Never change a shell's ID format without a migration.
- `KeyStore.StoreAPIKey` runs on a Go thread. It must return only after a durable write (Android `commit()` plus read-back, never `apply()`; no plaintext fallback). It must not block on the shell's own engine executor, or it deadlocks. Every request reads the key once and signs with that value. See AGENTS.md, "Rotated API keys".

## 6. Repo-mechanical invariants

- `version` in `branchdam.go` (`x-release-please-version`) and `CFBundleShortVersionString` in `ios/BranchDAM/Info.plist` are bumped only by release-please. `repo_resources_test.go` fails if either differs from `.release-please-manifest.json`.
- Android `values/strings*.xml` files are merged into one table, so a name must be unique across all of them. Any string with more than one format argument must use positional forms (`%1$s`). iOS `Localizable.strings` and `.stringsdict` are also checked by root Go tests.
- `core/client/testdata/contract/*.json` and `SOURCE` come from branchDAM through `make contract-sync`. Never hand-edit them.
- `third_party/modernc.org/libc` is a patched overlay pinned to the libc version in both `go.mod` files. A sqlite or libc bump needs `scripts/update-libc-overlay.sh <version>`, and `make lint` enforces this. The overlay exists because of Android seccomp (raw `lstat`, #211).
- There are two Go modules, the root (`branchdam`, the gomobile package) and `core/`. Tests, `go vet`, `gofmt` and `go mod tidy` run in both.
