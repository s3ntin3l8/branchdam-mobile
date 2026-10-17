package engine

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
	"github.com/s3ntin3l8/branchdam-mobile/core/hasher"
	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

// isNotFoundErr reports whether err is the (database/sql).ErrNoRows
// sentinel from a missing-row scan. The engine treats "not found" as
// a normal "ineligible" outcome (no error) so the branchdam FFI
// surface can distinguish it from "ineligible, server said no".
func isNotFoundErr(err error) bool {
	return errors.Is(err, sql.ErrNoRows)
}

// ErrLocalFlagSetFailed is wrapped by the engine's SafeSpaceReclaim
// when the local SetMediaOffloaded write fails. The branchdam FFI
// surface matches on this sentinel (via errors.Is) to distinguish
// transient local-DB failures from genuine ineligible verdicts —
// without depending on the error message string.
var ErrLocalFlagSetFailed = errors.New("local flag set failed")

// ErrLocalReadFailed is wrapped by the engine's SafeSpaceReclaim
// when the local state-lookup fails with a non-ErrNoRows DB error
// (e.g. SQLITE_BUSY, corruption). The FFI surface maps this to
// DB_ERROR (transient, retry) — distinct from the ineligible
// verdict that an ErrNoRows "not found" produces.
var ErrLocalReadFailed = errors.New("local read failed")

type ActiveUploadProgress struct {
	ID               int64   `json:"id"`
	Filename         string  `json:"filename"`
	BytesSent        int64   `json:"bytesSent"`
	TotalBytes       int64   `json:"totalBytes"`
	SpeedBytesPerSec float64 `json:"speedBytesPerSec"`
	ItemIndex        int     `json:"itemIndex"`
	TotalItems       int     `json:"totalItems"`
}

type Engine struct {
	q *queue.Queue
	c *client.Client

	// cancelRequested is an atomic flag that the shell's SetCancelFlag
	// (via the branchdam FFI) sets and SyncUploads / SyncEvents read
	// + reset at the start of each batch. In-process atomic (not a
	// SQLite column) so a single cancel never persists across
	// syncs — this was the critical bug Hermes flagged in B.2.2.
	cancelRequested atomic.Bool

	// syncMu serialises SyncUploads / SyncEvents within the process, so
	// while one runs every IN_PROGRESS row it did not claim is an orphan.
	syncMu sync.Mutex

	// inflightCancel cancels the upload currently streaming, so
	// RequestCancel stops it mid-body rather than after it finishes.
	inflightMu     sync.Mutex
	inflightCancel context.CancelFunc

	// uploadBudget overrides UploadBudget; tests only.
	uploadBudget func(sizeBytes int64) time.Duration

	activeMu         sync.Mutex
	activeUpload     *ActiveUploadProgress
	lastProgressTime time.Time
	lastProgressSent int64
}

func (e *Engine) setActiveUpload(id int64, filename string, totalBytes int64, itemIndex int, totalItems int) {
	e.activeMu.Lock()
	defer e.activeMu.Unlock()
	now := time.Now()
	e.activeUpload = &ActiveUploadProgress{
		ID:               id,
		Filename:         filename,
		BytesSent:        0,
		TotalBytes:       totalBytes,
		SpeedBytesPerSec: 0,
		ItemIndex:        itemIndex,
		TotalItems:       totalItems,
	}
	e.lastProgressTime = now
	e.lastProgressSent = -1
}

func (e *Engine) updateActiveUploadProgress(bytesSent int64, totalBytes int64) {
	e.activeMu.Lock()
	defer e.activeMu.Unlock()
	if e.activeUpload == nil {
		return
	}
	now := time.Now()
	if e.lastProgressSent < 0 {
		e.lastProgressTime = now
		e.lastProgressSent = bytesSent
	} else {
		dt := now.Sub(e.lastProgressTime).Seconds()
		if dt >= 0.2 {
			dBytes := bytesSent - e.lastProgressSent
			if dBytes > 0 && dt > 0 {
				e.activeUpload.SpeedBytesPerSec = float64(dBytes) / dt
			}
			e.lastProgressTime = now
			e.lastProgressSent = bytesSent
		}
	}
	e.activeUpload.BytesSent = bytesSent
	if totalBytes > 0 {
		e.activeUpload.TotalBytes = totalBytes
	}
}

func (e *Engine) clearActiveUpload() {
	e.activeMu.Lock()
	defer e.activeMu.Unlock()
	e.activeUpload = nil
}

func (e *Engine) GetActiveUploadProgress() (*ActiveUploadProgress, error) {
	e.activeMu.Lock()
	defer e.activeMu.Unlock()
	if e.activeUpload == nil {
		return nil, nil
	}
	cp := *e.activeUpload
	return &cp, nil
}

// RequestCancel sets the in-process cancel flag and aborts the upload
// currently streaming, if any. The running SyncUploads / SyncEvents call
// observes it, returns every row it had claimed but not finished to
// PENDING, and returns the partial-sync counts; subsequent syncs are
// unaffected.
func (e *Engine) RequestCancel() {
	e.cancelRequested.Store(true)
	e.inflightMu.Lock()
	cancel := e.inflightCancel
	e.inflightMu.Unlock()
	if cancel != nil {
		cancel()
	}
}

type SafeSpaceCandidate struct {
	LocalID        string `json:"localId"`
	NodeUUID       string `json:"nodeUuid"`
	Blake3Hash     string `json:"blake3Hash"`
	IsVerified     bool   `json:"isVerified"`
	IsEligible     bool   `json:"isEligible"`
	Tier           string `json:"tier"`
	LifecycleState string `json:"lifecycleState"`
	// Reason names the first reclaim gate the candidate failed. Empty
	// when IsEligible is true.
	Reason string `json:"reason,omitempty"`
}

// ReclaimTier is the only server storage tier whose copy authorises
// deleting the phone's original: the Tier-3 master archive. The server's
// tiers are TIER0_LOCAL_STAGING, TIER1_LOCAL_SCRATCH, TIER2_EXPORTS,
// TIER3_MASTER_ARCHIVE and PROJECTS; none but Tier 3 is a durable master.
const ReclaimTier = "TIER3_MASTER_ARCHIVE"

// reclaimLiveLifecycleStates are the server lifecycle states in which the
// archived master still exists and is reachable -- the same "live"
// definition the server's node-status endpoint and edge views use.
// TRASHED, MISSING and ARCHIVED (superseded) masters must never authorise a
// reclaim: deleting the phone copy would leave no live copy anywhere.
var reclaimLiveLifecycleStates = map[string]bool{
	"ACTIVE": true,
	"HIDDEN": true,
}

// Reclaim reason codes for the content-identity gate (#217). Each is the
// leading token of the Reason string the shells display verbatim, matching
// the "<gate>: <detail>" shape of the other reclaim reasons.
const (
	// ReasonNoServerHash: node-status returned no fullHash, so the
	// server's copy cannot be proven to be these bytes.
	ReasonNoServerHash = "no-server-hash"
	// ReasonHashMismatch: node-status returned a fullHash that differs
	// from the BLAKE3 recorded locally at ingest.
	ReasonHashMismatch = "hash-mismatch"
	// ReasonNoLocalHash: queue.db holds no BLAKE3 for this item, so there
	// is nothing to compare the server's fullHash against.
	ReasonNoLocalHash = "no-local-hash"
	// ReasonLocalChanged: the local item's current size or modification
	// time, as the shell read it just before the reclaim, differs from the
	// baseline recorded with its BLAKE3. Its bytes may have been replaced
	// since upload, so the recorded hash no longer proves the server holds
	// them (issue #229).
	ReasonLocalChanged = "local-changed"
	// ReasonNoLocalBaseline: queue.db holds no size or modification time
	// for this item (recorded before the baseline existed, or by a shell
	// that supplied none), so a change since upload cannot be ruled out.
	ReasonNoLocalBaseline = "no-local-baseline"
)

// LocalFileState is a local media item's size and modification time (unix
// seconds) as the shell reads them from the platform media store (Android
// MediaStore SIZE / DATE_MODIFIED, iOS PHAsset modificationDate, else creationDate). The core
// cannot open the original on either platform -- it only ever sees a staged
// copy -- so the shell supplies it: at enqueue, recorded as the reclaim
// baseline next to the BLAKE3, and again immediately before a reclaim
// deletes the item. A zero field means the platform does not report it.
type LocalFileState struct {
	SizeBytes    int64
	ModifiedUnix int64
}

// localChangedReason compares the state the shell read just before a
// reclaim (current) with the baseline recorded with the item's BLAKE3 and
// returns "" when the item is provably unchanged, or a reason otherwise.
// This mirrors branchdam-agent's prune, which re-checks disk size and
// mtime immediately before deleting: the BLAKE3 in queue.db describes the
// bytes at enqueue, and a localID whose bytes were replaced in place since
// would otherwise be deleted on the strength of a stale-but-matching hash.
// Each field the baseline recorded (non-zero) must match exactly; a row
// with no baseline at all is kept.
func localChangedReason(recorded *queue.LocalMediaState, current LocalFileState) string {
	if recorded.SizeBytes <= 0 && recorded.ModifiedUnix <= 0 {
		return ReasonNoLocalBaseline + ": no size or modification time recorded locally"
	}
	if recorded.SizeBytes > 0 && current.SizeBytes != recorded.SizeBytes {
		return fmt.Sprintf("%s: size %d differs from %d recorded at enqueue", ReasonLocalChanged, current.SizeBytes, recorded.SizeBytes)
	}
	if recorded.ModifiedUnix > 0 && current.ModifiedUnix != recorded.ModifiedUnix {
		return fmt.Sprintf("%s: modification time %d differs from %d recorded at enqueue", ReasonLocalChanged, current.ModifiedUnix, recorded.ModifiedUnix)
	}
	return ""
}

// reclaimIneligibleReason evaluates one node-status entry against every
// reclaim gate and returns "" when the local original may be deleted, or a
// reason naming the first gate that failed otherwise. Gates, in order:
// found on the server, hash-verified, on the Tier-3 master archive, in a
// live lifecycle state (ACTIVE or HIDDEN), and -- last -- the server's
// stored BLAKE3 fullHash equal (case-insensitively) to localHash, the
// BLAKE3 recorded in queue.db at ingest. A missing hash on either side
// keeps the file: verified alone never authorises a delete.
func reclaimIneligibleReason(st client.NodeStatusItem, localHash string) string {
	serverHash := strings.TrimSpace(st.FullHash)
	localHash = strings.TrimSpace(localHash)
	switch {
	case !st.Found:
		return "node not found on server"
	case !st.Verified:
		return "not verified on server"
	case st.Tier != ReclaimTier:
		return "tier ineligible: " + orNone(st.Tier)
	case !reclaimLiveLifecycleStates[st.LifecycleState]:
		return "lifecycle ineligible: " + orNone(st.LifecycleState)
	case serverHash == "":
		return ReasonNoServerHash + ": server returned no fullHash"
	case localHash == "":
		return ReasonNoLocalHash + ": no BLAKE3 hash recorded locally"
	case !strings.EqualFold(serverHash, localHash):
		return ReasonHashMismatch + ": server fullHash differs from local hash"
	}
	return ""
}

func orNone(v string) string {
	if v == "" {
		return "<none>"
	}
	return v
}

// SafeSpaceVerdict is the engine's per-candidate verdict. Reason is
// empty when Eligible is true.
type SafeSpaceVerdict struct {
	LocalID  string
	Eligible bool
	Reason   string
}

func New(q *queue.Queue, c *client.Client) *Engine {
	return &Engine{
		q: q,
		c: c,
	}
}

// EnqueueLocalCapture reads a local media file, calculates hashes, records local state, and queues for upload.
// It records no reclaim baseline; see EnqueueLocalCaptureWithBaseline.
func (e *Engine) EnqueueLocalCapture(localPath, filename string, capturedAtUnix int64, localID string, cameraModel, sourcePathHash string) (*queue.UploadItem, error) {
	return e.EnqueueLocalCaptureWithBaseline(localPath, filename, capturedAtUnix, localID, cameraModel, sourcePathHash, LocalFileState{})
}

// EnqueueLocalCaptureWithBaseline is EnqueueLocalCapture that also records
// baseline -- the original item's media-store size and modification time,
// read by the shell before it staged the bytes at localPath -- next to the
// BLAKE3 for localID. SafeSpaceReclaim compares it with the item's state
// just before deletion. Reading it before staging keeps the order
// fail-safe: a change between the read and the copy leaves the baseline
// describing the older bytes, so the reclaim sees a mismatch and keeps.
func (e *Engine) EnqueueLocalCaptureWithBaseline(localPath, filename string, capturedAtUnix int64, localID string, cameraModel, sourcePathHash string, baseline LocalFileState) (*queue.UploadItem, error) {
	srcPathHash := strings.ToLower(strings.TrimSpace(sourcePathHash))
	if srcPathHash != "" {
		if len(srcPathHash) != 64 || !client.IsLowerHex(srcPathHash) {
			return nil, &client.ClientError{
				Code:    client.CodeInvalidInput,
				Message: fmt.Sprintf("invalid sourcePathHash %q: must be 64 lowercase hex characters", sourcePathHash),
			}
		}
	}

	// B.2.5: Stat before Open so a missing-file failure surfaces with
	// the canonical IO_ERROR code at the FFI boundary rather than a
	// generic open error.
	if _, err := os.Stat(localPath); err != nil {
		if os.IsNotExist(err) {
			return nil, &client.ClientError{
				Code:    client.CodeIOError,
				Message: fmt.Sprintf("local file does not exist: %s", localPath),
				Cause:   err,
			}
		}
		return nil, &client.ClientError{
			Code:    client.CodeIOError,
			Message: fmt.Sprintf("stat local file: %v", err),
			Cause:   err,
		}
	}

	file, err := os.Open(localPath)
	if err != nil {
		return nil, &client.ClientError{
			Code:    client.CodeIOError,
			Message: fmt.Sprintf("open local file: %v", err),
			Cause:   err,
		}
	}
	// B.2.5: defer close before the first non-error return.
	defer file.Close()

	fastHash, fullHash, sizeBytes, mtime, err := hashOpenFile(file)
	if err != nil {
		return nil, &client.ClientError{
			Code:    client.CodeIOError,
			Message: fmt.Sprintf("hash local file: %v", err),
			Cause:   err,
		}
	}

	// Reset any previous FAILED entry for this file so re-enqueueing recovers it
	_ = e.q.ResetUploadByBlake3Hash(fullHash, localPath, sizeBytes, mtime)

	// Dedup gate: check if this blake3Hash is already queued or uploaded
	if existing, err := e.q.GetUploadItemByBlake3Hash(fullHash); err == nil && existing != nil {
		if localID != "" {
			_ = e.q.RecordLocalMediaWithBaseline(localID, existing.NodeUUID, fullHash, "ACTIVE", baseline.SizeBytes, baseline.ModifiedUnix)
		}
		return existing, nil
	}

	cam := cameraModel
	if cam == "" && e.c != nil {
		cam = e.c.AgentID()
	}

	if srcPathHash == "" {
		if localID != "" {
			// Stable across iOS app launches where sandbox container UUID changes
			h := sha256.Sum256([]byte(localID))
			srcPathHash = hex.EncodeToString(h[:])
		} else if localPath != "" {
			h := sha256.Sum256([]byte(localPath))
			srcPathHash = hex.EncodeToString(h[:])
		}
	}

	item := &queue.UploadItem{
		LocalPath:      localPath,
		TargetFilename: filename,
		FastHash:       fastHash,
		Blake3Hash:     fullHash,
		CameraModel:    cam,
		SourcePathHash: srcPathHash,
		SizeBytes:      sizeBytes,
		CapturedAtUnix: capturedAtUnix,
		// Compared with the file just before upload: a change means
		// the hashes above are stale (see fileChangedSinceEnqueue).
		ModTimeUnixNano: mtime,
	}

	id, err := e.q.EnqueueUpload(item)
	if err != nil {
		return nil, fmt.Errorf("failed to enqueue upload: %w", err)
	}
	item.ID = id

	if localID != "" {
		_ = e.q.RecordLocalMediaWithBaseline(localID, "", fullHash, "ACTIVE", baseline.SizeBytes, baseline.ModifiedUnix)
	}

	return item, nil
}

// Retry and recovery defaults. SyncPolicy's zero value means these.
const (
	// DefaultRetryBackoffSecs is the base of the exponential retry
	// backoff: a row that was attempted before waits
	// base << retry_count seconds (capped at
	// queue.DefaultMaxRetryBackoffSecs, one hour) before it is claimed
	// again.
	DefaultRetryBackoffSecs = 10
	// DefaultMaxRetries is how many failed attempts move a row to FAILED.
	DefaultMaxRetries = 5
	// StaleClaimAge is how long a row may stay IN_PROGRESS before a sync
	// treats its claim as orphaned and hands it back. Every sync path
	// releases what it claimed before returning and syncs in one process
	// are serialised, so a row this old belongs to a sync that panicked,
	// failed to write its release, or ran in a process that died; the
	// margin keeps it far clear of any claim still being worked on.
	StaleClaimAge = 30 * time.Minute
)

// Per-upload time budget. An upload is not bound by the batch deadline
// (SyncBatch's TimeoutSecs), which only stops new items from starting:
// a large video on a slow uplink would otherwise be cut off mid-stream on
// every attempt and burn its retries without ever finishing. Instead each
// upload gets UploadBaseBudget plus the time its size takes at
// UploadMinThroughputBytesPerSec, or the rest of the batch deadline if
// that is later. 128 KiB/s (about 1 Mbit/s) is a conservative floor for
// a usable mobile uplink; an upload slower than that across its whole
// length counts as a failed attempt. The caller's cancellation
// (SetCancelFlag, or a cancelled context) still stops an upload at once.
const (
	UploadBaseBudget               = 2 * time.Minute
	UploadMinThroughputBytesPerSec = 128 * 1024
)

// UploadBudget returns how long one upload of sizeBytes may take.
func UploadBudget(sizeBytes int64) time.Duration {
	if sizeBytes < 0 {
		sizeBytes = 0
	}
	return UploadBaseBudget + time.Duration(sizeBytes/UploadMinThroughputBytesPerSec+1)*time.Second
}

// SyncPolicy carries the retry options of one sync. Zero fields take the
// defaults above.
type SyncPolicy struct {
	// RetryBackoffSecs is the base of the exponential retry backoff.
	RetryBackoffSecs int64
	// MaxRetries is how many failed attempts move a row to FAILED.
	MaxRetries int
}

func (p SyncPolicy) normalized() SyncPolicy {
	if p.RetryBackoffSecs <= 0 {
		p.RetryBackoffSecs = DefaultRetryBackoffSecs
	}
	if p.MaxRetries <= 0 {
		p.MaxRetries = DefaultMaxRetries
	}
	return p
}

// reclaimStaleClaims hands back rows left IN_PROGRESS for longer than
// StaleClaimAge. Best-effort: a failure is logged and the sync goes on.
func (e *Engine) reclaimStaleClaims() {
	uploads, events, err := e.q.ReclaimStaleClaims(StaleClaimAge)
	if err != nil {
		slog.Warn("engine: reclaiming stale claims failed", "err", err)
		return
	}
	if uploads > 0 || events > 0 {
		slog.Warn("engine: reclaimed orphaned IN_PROGRESS rows", "uploads", uploads, "events", events)
	}
}

// interrupted reports whether the caller asked the current sync to stop:
// SetCancelFlag, or a context that was cancelled (not merely past its
// deadline; see uploadContext).
func (e *Engine) interrupted(ctx context.Context) bool {
	return e.cancelRequested.Load() || errors.Is(ctx.Err(), context.Canceled)
}

func (e *Engine) releaseUploads(items []*queue.UploadItem) {
	if len(items) == 0 {
		return
	}
	ids := make([]int64, len(items))
	for i, it := range items {
		ids[i] = it.ID
	}
	if err := e.q.ReleaseUploads(ids); err != nil {
		// The rows stay IN_PROGRESS until the stale-claim reclaim.
		slog.Warn("engine: releasing claimed uploads failed", "count", len(ids), "err", err)
	}
}

func (e *Engine) releaseEvents(events []*queue.EventItem) {
	if len(events) == 0 {
		return
	}
	ids := make([]int64, len(events))
	for i, it := range events {
		ids[i] = it.ID
	}
	if err := e.q.ReleaseEvents(ids); err != nil {
		slog.Warn("engine: releasing claimed events failed", "count", len(ids), "err", err)
	}
}

// uploadContext derives the context one upload runs under from the batch
// context: it has its own deadline (budget, or the batch deadline if that
// is later), ignores the batch deadline expiring, and is cancelled when
// the batch context is cancelled or SetCancelFlag is called. done must be
// called once the upload returns.
func (e *Engine) uploadContext(batch context.Context, budget time.Duration) (ctx context.Context, done func()) {
	deadline := time.Now().Add(budget)
	if d, ok := batch.Deadline(); ok && d.After(deadline) {
		deadline = d
	}
	ctx, cancel := context.WithDeadline(context.WithoutCancel(batch), deadline)
	stop := context.AfterFunc(batch, func() {
		if errors.Is(batch.Err(), context.Canceled) {
			cancel()
		}
	})

	e.inflightMu.Lock()
	e.inflightCancel = cancel
	e.inflightMu.Unlock()
	// A SetCancelFlag that landed before inflightCancel was set would
	// otherwise be missed until the upload finished.
	if e.cancelRequested.Load() {
		cancel()
	}

	return ctx, func() {
		e.inflightMu.Lock()
		e.inflightCancel = nil
		e.inflightMu.Unlock()
		stop()
		cancel()
	}
}

// SyncUploads processes a batch of pending uploads with the default
// SyncPolicy. See SyncUploadsWithPolicy.
func (e *Engine) SyncUploads(ctx context.Context, batchSize int) (int, error) {
	return e.SyncUploadsWithPolicy(ctx, batchSize, SyncPolicy{})
}

// SyncUploadsWithPolicy claims a batch of pending uploads and streams them
// to the server, returning how many completed.
//
// Every claimed row leaves IN_PROGRESS before this returns. Rows the batch
// never reached, and a row whose upload was interrupted by the caller, go
// back to PENDING without using a retry; the batch context's deadline only
// stops new uploads from starting (an upload in flight runs under its own
// budget, see UploadBudget). A 401/403 from the server stops the batch the
// same way and is returned as a client.ClientError with CodeAuthFailed:
// the device must be re-paired, and no row should exhaust its retries in
// the meantime.
func (e *Engine) SyncUploadsWithPolicy(ctx context.Context, batchSize int, policy SyncPolicy) (int, error) {
	if batchSize <= 0 {
		batchSize = 5
	}
	p := policy.normalized()

	// Syncs in one process run one at a time, so no claim this process
	// holds can be mistaken for an orphan by reclaimStaleClaims.
	e.syncMu.Lock()
	defer e.syncMu.Unlock()

	// B.2.2: capture + reset the cancel flag at the start of each
	// SyncBatch. The flag is in-process (atomic.Bool), so a single
	// SetCancelFlag only affects the in-flight sync. The shell can
	// safely re-sync after a cancel without being stuck.
	cancelled := e.cancelRequested.Swap(false)

	if cancelled {
		slog.Info("engine: sync cancelled before claim")
		return 0, ctx.Err()
	}

	e.reclaimStaleClaims()

	items, err := e.q.ClaimPendingUploads(batchSize, p.RetryBackoffSecs, p.MaxRetries)
	if err != nil {
		return 0, fmt.Errorf("claim uploads failed: %w", err)
	}

	defer e.clearActiveUpload()

	completedCount := 0
	for i, item := range items {
		// B.2.2: also check the flag between items so a
		// SetCancelFlag mid-batch halts gracefully. Unstarted items
		// go back to PENDING untouched.
		if e.cancelRequested.Load() || ctx.Err() != nil {
			e.releaseUploads(items[i:])
			return completedCount, ctx.Err()
		}

		e.setActiveUpload(item.ID, item.TargetFilename, item.SizeBytes, i+1, len(items))

		outcome, upErr := e.uploadPendingItem(ctx, item, p)
		e.clearActiveUpload()
		switch outcome {
		case uploadCompleted:
			completedCount++
		case uploadReleased:
			e.releaseUploads(items[i+1:])
			return completedCount, ctx.Err()
		case uploadAuthFailed:
			e.releaseUploads(items[i+1:])
			return completedCount, upErr
		}
	}

	return completedCount, nil
}

type uploadOutcome int

const (
	// uploadFailed: the attempt was recorded against the row (a retry
	// counted, or a terminal failure); the batch goes on.
	uploadFailed uploadOutcome = iota
	uploadCompleted
	// uploadReleased: the caller interrupted the upload, or the batch
	// deadline passed before it started; the row is back to PENDING with
	// no retry used and the batch stops.
	uploadReleased
	// uploadAuthFailed: the server rejected the device's credentials; the
	// row is back to PENDING with no retry used and the batch stops.
	uploadAuthFailed
)

// fileChangedSinceEnqueue reports whether the file at an upload's path no
// longer matches what was hashed at enqueue: a different size, or (on rows
// that recorded it) a different modification time.
func fileChangedSinceEnqueue(item *queue.UploadItem, info os.FileInfo) bool {
	if info.Size() != item.SizeBytes {
		return true
	}
	return item.ModTimeUnixNano != 0 && info.ModTime().UnixNano() != item.ModTimeUnixNano
}

// hashOpenFile hashes file from its current offset and returns the
// modification time it had before hashing. Recording the pre-hash mtime
// means a write that lands during the hash leaves a stored mtime that no
// longer matches, so the pre-upload re-stat catches it.
func hashOpenFile(file *os.File) (fastHash, fullHash string, sizeBytes, mtimeUnixNano int64, err error) {
	info, err := file.Stat()
	if err != nil {
		return "", "", 0, 0, fmt.Errorf("stat: %w", err)
	}
	fastHash, fullHash, sizeBytes, err = hasher.HashReader(file, info.Size())
	if err != nil {
		return "", "", 0, 0, err
	}
	return fastHash, fullHash, sizeBytes, info.ModTime().UnixNano(), nil
}

// refreshChangedUpload re-hashes an upload whose file changed after it was
// enqueued and records the new hashes, so the bytes are not sent with a
// stale hash the server would reject on every retry. file is rewound for
// streaming afterwards.
func (e *Engine) refreshChangedUpload(file *os.File, item *queue.UploadItem) (*queue.UploadItem, error) {
	fastHash, fullHash, sizeBytes, mtime, err := hashOpenFile(file)
	if err != nil {
		return nil, fmt.Errorf("re-hash: %w", err)
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return nil, fmt.Errorf("rewind: %w", err)
	}
	if err := e.q.UpdateUploadContent(item.ID, fastHash, fullHash, sizeBytes, mtime); err != nil {
		return nil, fmt.Errorf("record new hashes: %w", err)
	}
	slog.Info("engine: file changed since enqueue; re-hashed before upload",
		"id", item.ID, "localPath", item.LocalPath,
		"oldSize", item.SizeBytes, "newSize", sizeBytes, "hashChanged", fullHash != item.Blake3Hash)
	refreshed := *item
	refreshed.FastHash = fastHash
	refreshed.Blake3Hash = fullHash
	refreshed.SizeBytes = sizeBytes
	refreshed.ModTimeUnixNano = mtime
	return &refreshed, nil
}

// preScreen asks the server whether it already holds item's content (by
// hash) and, if so, completes the row with that node and reports true so
// the payload is not streamed. Any error means "not known": upload anyway.
func (e *Engine) preScreen(ctx context.Context, item *queue.UploadItem) bool {
	if e.c == nil {
		return false
	}
	checkRes, checkErr := e.c.CheckContent(ctx, item.FastHash, item.Blake3Hash)
	if checkErr != nil || !checkRes.Found || checkRes.NodeUUID == "" {
		return false
	}
	slog.Info("engine: background sync pre-screen dedup — content already exists on server",
		"nodeUUID", checkRes.NodeUUID, "localPath", item.LocalPath)
	_ = e.q.MarkUploadComplete(item.ID, checkRes.NodeUUID)
	if err := e.q.UpdateLocalMediaNodeUUID(item.Blake3Hash, checkRes.NodeUUID); err != nil {
		slog.Warn("engine: failed to update local media state nodeUUID", "blake3", item.Blake3Hash, "err", err)
	}
	return true
}

// uploadPendingItem attempts to open, re-check, pre-screen and stream
// upload a single claimed item, and records the outcome on its row. Uses a helper method
// so defer file.Close() runs cleanly per-item.
func (e *Engine) uploadPendingItem(ctx context.Context, item *queue.UploadItem, p SyncPolicy) (uploadOutcome, error) {
	// B.2.5: Stat before Open so a missing file surfaces as IO_ERROR.
	// An unreadable file can't have changed into other content, so the
	// server may still already hold what was hashed at enqueue.
	info, statErr := os.Stat(item.LocalPath)
	if statErr != nil {
		if e.preScreen(ctx, item) {
			return uploadCompleted, nil
		}
		_ = e.q.MarkUploadFailed(item.ID, fmt.Sprintf("file stat failed: %v", statErr), p.MaxRetries)
		return uploadFailed, statErr
	}

	file, openErr := os.Open(item.LocalPath)
	if openErr != nil {
		if e.preScreen(ctx, item) {
			return uploadCompleted, nil
		}
		_ = e.q.MarkUploadFailed(item.ID, fmt.Sprintf("file open failed: %v", openErr), p.MaxRetries)
		return uploadFailed, openErr
	}
	// B.2.5: defer close inside helper method so cleanup is guaranteed.
	defer file.Close()

	if fileChangedSinceEnqueue(item, info) {
		refreshed, err := e.refreshChangedUpload(file, item)
		if err != nil {
			_ = e.q.MarkUploadFailed(item.ID, fmt.Sprintf("file changed since enqueue: %v", err), p.MaxRetries)
			return uploadFailed, err
		}
		item = refreshed
		e.updateActiveUploadProgress(0, item.SizeBytes)
	}

	// Runs after the re-stat so it asks about the bytes on disk now.
	if e.preScreen(ctx, item) {
		return uploadCompleted, nil
	}
	// The batch ran out of time (or was cancelled) before the upload
	// started: don't start one it can no longer afford.
	if e.cancelRequested.Load() || ctx.Err() != nil {
		e.releaseUploads([]*queue.UploadItem{item})
		return uploadReleased, ctx.Err()
	}

	cam := item.CameraModel
	if cam == "" && e.c != nil {
		cam = e.c.AgentID()
	}

	uploadOpts := client.UploadOptions{
		CameraModel:    cam,
		FastHash:       item.FastHash,
		Blake3Hash:     item.Blake3Hash,
		SourcePathHash: item.SourcePathHash,
		CapturedAtUnix: item.CapturedAtUnix,
		ProgressFn: func(bytesSent int64, totalBytes int64) {
			e.updateActiveUploadProgress(bytesSent, totalBytes)
		},
	}

	budget := UploadBudget(item.SizeBytes)
	if e.uploadBudget != nil {
		budget = e.uploadBudget(item.SizeBytes)
	}
	uploadCtx, done := e.uploadContext(ctx, budget)
	resp, uploadErr := e.c.UploadStream(uploadCtx, file, item.SizeBytes, item.TargetFilename, uploadOpts)
	done()
	if uploadErr != nil {
		// Interrupted by the caller: the attempt says nothing about the
		// item, so it goes back to PENDING without using a retry.
		if e.interrupted(ctx) {
			slog.Info("engine: upload interrupted; item returned to queue", "id", item.ID, "localPath", item.LocalPath)
			e.releaseUploads([]*queue.UploadItem{item})
			return uploadReleased, uploadErr
		}
		if client.IsAuthError(uploadErr) {
			slog.Warn("engine: server rejected device credentials; stopping upload batch", "id", item.ID)
			e.releaseUploads([]*queue.UploadItem{item})
			return uploadAuthFailed, uploadErr
		}
		// B.2.6: handle the new structured dedup / hash-mismatch
		// codes by surfacing them as typed errors; the engine
		// distinguishes "soft dedup, mark complete" from "hard
		// dedup failure, re-queue" via the Code.
		var ce *client.ClientError
		if errors.As(uploadErr, &ce) {
			switch ce.Code {
			case client.CodeDedupNoNodeUUID, client.CodeHashMismatch, client.CodeResponseTooLarge:
				// Hard failure; mark as failed so it retries.
				_ = e.q.MarkUploadFailed(item.ID, uploadErr.Error(), p.MaxRetries)
				return uploadFailed, uploadErr
			}
		}
		if dedupResp, ok := client.AsDedupResponse(uploadErr); ok {
			slog.Info("engine: upload dedup — server returned existing node",
				"existingUUID", dedupResp.NodeUUID, "localPath", item.LocalPath)
			if err := e.q.MarkUploadComplete(item.ID, dedupResp.NodeUUID); err != nil {
				// Same as the success path below: hand the row back so
				// the next sync asks again instead of stranding it.
				e.releaseUploads([]*queue.UploadItem{item})
				return uploadFailed, err
			}
			// Record the node on the local media row exactly as the
			// pre-screen and success paths do; without it lineage events
			// and Safe Space reclaim can't resolve this item's node.
			if err := e.q.UpdateLocalMediaNodeUUID(item.Blake3Hash, dedupResp.NodeUUID); err != nil {
				slog.Warn("engine: failed to update local media state nodeUUID", "blake3", item.Blake3Hash, "err", err)
			}
			return uploadCompleted, nil
		}
		// Anything else, including the upload running past its own
		// budget, is a failed attempt.
		_ = e.q.MarkUploadFailed(item.ID, uploadErr.Error(), p.MaxRetries)
		return uploadFailed, uploadErr
	}

	if resp.IsDedup {
		slog.Info("engine: upload dedup — server acknowledged existing content via X-Dedup",
			"existingUUID", resp.NodeUUID, "localPath", item.LocalPath)
	}

	if err := e.q.MarkUploadComplete(item.ID, resp.NodeUUID); err != nil {
		// The row is still IN_PROGRESS; hand it back so the next sync
		// re-sends it (the server answers with a dedup).
		e.releaseUploads([]*queue.UploadItem{item})
		return uploadFailed, err
	}
	if err := e.q.UpdateLocalMediaNodeUUID(item.Blake3Hash, resp.NodeUUID); err != nil {
		slog.Warn("engine: failed to update local media state nodeUUID", "blake3", item.Blake3Hash, "err", err)
	}

	return uploadCompleted, nil
}

// SyncEvents dispatches pending events with the default SyncPolicy. See
// SyncEventsWithPolicy.
func (e *Engine) SyncEvents(ctx context.Context, batchSize int) (int, error) {
	return e.SyncEventsWithPolicy(ctx, batchSize, SyncPolicy{})
}

// SyncEventsWithPolicy dispatches pending lifecycle events to the central
// branchDAM server and returns how many were accepted. Lineage and delete
// events are translated from their queued local-ID form into the server's
// schema first (see planEvent): an event whose media has not reached the
// server yet is deferred without using a retry, one with nothing to act on
// is SKIPPED, and one the server could never accept is FAILED without
// being sent. A 4xx answer other than 401/403/408/429 is terminal for that
// event (FAILED with the status and response body), so one bad payload
// cannot jam the queue; transport errors, 5xx, 408 and 429 are retried.
// A 401/403 stops the batch and is returned as a client.ClientError with
// CodeAuthFailed, and cancellation or the batch deadline stops it too: in
// those cases the event in flight and every event not yet reached go back
// to PENDING without using a retry.
func (e *Engine) SyncEventsWithPolicy(ctx context.Context, batchSize int, policy SyncPolicy) (int, error) {
	if batchSize <= 0 {
		batchSize = 10
	}
	p := policy.normalized()

	e.syncMu.Lock()
	defer e.syncMu.Unlock()

	// B.2.2: see SyncUploads.
	cancelled := e.cancelRequested.Swap(false)

	if cancelled {
		slog.Info("engine: event sync cancelled before claim")
		return 0, ctx.Err()
	}

	e.reclaimStaleClaims()

	events, err := e.q.ClaimPendingEvents(batchSize, p.RetryBackoffSecs, p.MaxRetries)
	if err != nil {
		return 0, fmt.Errorf("claim events failed: %w", err)
	}

	sentCount := 0
	for i, evt := range events {
		if e.cancelRequested.Load() || ctx.Err() != nil {
			e.releaseEvents(events[i:])
			return sentCount, ctx.Err()
		}

		plan := e.planEvent(evt)
		switch plan.action {
		case actionDefer:
			_ = e.q.MarkEventDeferred(evt.ID, plan.reason)
			continue
		case actionSkip:
			slog.Info("engine: event not sent", "eventUUID", evt.EventUUID, "eventType", evt.EventType, "reason", plan.reason)
			_ = e.q.MarkEventTerminal(evt.ID, queue.EventSkipped, plan.reason)
			continue
		case actionFail:
			slog.Warn("engine: event dropped", "eventUUID", evt.EventUUID, "eventType", evt.EventType, "reason", plan.reason)
			_ = e.q.MarkEventTerminal(evt.ID, queue.EventFailed, plan.reason)
			continue
		case actionRetry:
			_ = e.q.MarkEventFailed(evt.ID, plan.reason, p.MaxRetries)
			continue
		}

		_, err := e.c.SubmitEvent(ctx, evt.EventUUID, evt.EventType, plan.payload)
		if err != nil {
			// Cancelled, or out of batch time, mid-request: not the
			// event's fault.
			if e.cancelRequested.Load() || ctx.Err() != nil {
				e.releaseEvents(events[i:])
				return sentCount, ctx.Err()
			}
			if client.IsAuthError(err) {
				slog.Warn("engine: server rejected device credentials; stopping event batch", "eventUUID", evt.EventUUID)
				e.releaseEvents(events[i:])
				return sentCount, err
			}
			var ce *client.ClientError
			if errors.As(err, &ce) && ce.StatusCode != 0 && !client.IsRetryableStatus(ce.StatusCode) {
				reason := fmt.Sprintf("server rejected event: http %d", ce.StatusCode)
				if ce.Body != "" {
					reason += ": " + ce.Body
				}
				slog.Warn("engine: event rejected by server", "eventUUID", evt.EventUUID, "eventType", evt.EventType, "status", ce.StatusCode)
				_ = e.q.MarkEventTerminal(evt.ID, queue.EventFailed, reason)
				continue
			}
			_ = e.q.MarkEventFailed(evt.ID, err.Error(), p.MaxRetries)
			continue
		}

		if err := e.q.MarkEventSent(evt.ID); err != nil {
			// Still IN_PROGRESS; hand it back. Re-sending reuses the
			// event UUID, which the server treats as idempotent.
			e.releaseEvents([]*queue.EventItem{evt})
			continue
		}

		sentCount++
	}

	return sentCount, nil
}

// CheckSafeSpaceCandidates queries the server to determine which local items are safely archived.
func (e *Engine) CheckSafeSpaceCandidates(ctx context.Context, localIDs []string) ([]SafeSpaceCandidate, error) {
	candidateMap := make(map[string]SafeSpaceCandidate, len(localIDs))
	var queryUUIDs []string
	idToLocal := make(map[string][]string)

	for _, localID := range localIDs {
		state, err := e.q.GetMediaByLocalID(localID)
		if err != nil || state == nil || state.NodeUUID == "" {
			candidateMap[localID] = SafeSpaceCandidate{
				LocalID:    localID,
				IsVerified: false,
				IsEligible: false,
				Reason:     "localID not found in local state",
			}
			continue
		}

		candidateMap[localID] = SafeSpaceCandidate{
			LocalID:    localID,
			NodeUUID:   state.NodeUUID,
			Blake3Hash: state.Blake3Hash,
			IsVerified: false,
			IsEligible: false,
			// Overwritten below if the server answers for this node.
			Reason: "server has no record of node",
		}
		if _, seen := idToLocal[state.NodeUUID]; !seen {
			queryUUIDs = append(queryUUIDs, state.NodeUUID)
		}
		idToLocal[state.NodeUUID] = append(idToLocal[state.NodeUUID], localID)
	}

	if len(queryUUIDs) > 0 {
		// GetNodeStatuses splits the query into server-sized batches
		// (client.MaxNodeStatusBatch), so a large library does not fail
		// the whole scan with a 400.
		statuses, err := e.c.GetNodeStatuses(ctx, queryUUIDs)
		if err != nil {
			return nil, fmt.Errorf("failed to query node statuses: %w", err)
		}

		for _, st := range statuses {
			localIDsForNode, exists := idToLocal[st.NodeUUID]
			if !exists {
				continue
			}

			for _, localID := range localIDsForNode {
				// Per localID: rows sharing a node may still carry
				// different recorded hashes, and each is checked
				// against its own.
				reason := reclaimIneligibleReason(st, candidateMap[localID].Blake3Hash)
				candidateMap[localID] = SafeSpaceCandidate{
					LocalID:        localID,
					NodeUUID:       st.NodeUUID,
					Blake3Hash:     candidateMap[localID].Blake3Hash,
					IsVerified:     st.Verified,
					IsEligible:     reason == "",
					Tier:           st.Tier,
					LifecycleState: st.LifecycleState,
					Reason:         reason,
				}
			}
		}
	}

	// Build stable output ordered by input localIDs
	candidates := make([]SafeSpaceCandidate, 0, len(localIDs))
	for _, localID := range localIDs {
		candidates = append(candidates, candidateMap[localID])
	}

	return candidates, nil
}

// SafeSpaceReclaim marks an asset as intentionally offloaded to suppress
// trashing deletion events. B.2.7: this is the engine-owned atomic
// reclaim — the server is re-queried to confirm the node is found,
// hash-verified, on the Tier-3 master archive, live (ACTIVE or HIDDEN)
// and holding a fullHash equal to the locally recorded BLAKE3, and the
// local flag is set only inside the same logical operation. If the server
// says "not eligible", the flag is not set and the verdict's Reason
// explains why.
//
// current is the item's media-store state as the shell read it
// immediately before calling (and so before deleting): it must match the
// baseline recorded with the BLAKE3 (see localChangedReason), otherwise
// the item is kept with ReasonLocalChanged or ReasonNoLocalBaseline. That
// last gate runs after the server's, so a server-side refusal is still
// the reason reported when both would fail.
//
// Returns SafeSpaceVerdict with Eligible=true on success; callers
// (the branchdam FFI surface) should only delete the local file
// after seeing Eligible=true.
func (e *Engine) SafeSpaceReclaim(ctx context.Context, localID string, current LocalFileState) (SafeSpaceVerdict, error) {
	if localID == "" {
		return SafeSpaceVerdict{Reason: "localID is required"}, errors.New("localID is required")
	}

	// Look up the current state in the local queue. A missing row
	// (sql.ErrNoRows from the GetMediaByLocalID scan) is a legitimate
	// "ineligible, not found" outcome — the engine returns a
	// SafeSpaceVerdict with Eligible=false and Reason="not found";
	// the second return value is nil so the caller can distinguish
	// "ineligible, not found" from "ineligible, server said no".
	state, err := e.q.GetMediaByLocalID(localID)
	if err != nil {
		if isNotFoundErr(err) {
			return SafeSpaceVerdict{
				LocalID:  localID,
				Eligible: false,
				Reason:   "localID not found in local state",
			}, nil
		}
		return SafeSpaceVerdict{LocalID: localID, Reason: "local state lookup: " + err.Error()},
			fmt.Errorf("%w: %s", ErrLocalReadFailed, err)
	}
	if state == nil || state.NodeUUID == "" {
		return SafeSpaceVerdict{
			LocalID:  localID,
			Eligible: false,
			Reason:   "localID not found in local state",
		}, nil
	}

	// Re-query the server for the current status of this node.
	statuses, err := e.c.GetNodeStatuses(ctx, []string{state.NodeUUID})
	if err != nil {
		return SafeSpaceVerdict{LocalID: localID, Reason: "server status query failed"},
			fmt.Errorf("get node status: %w", err)
	}
	// Only trust the entry the server returned for this node; an entry
	// for any other UUID says nothing about this asset.
	var st *client.NodeStatusItem
	for i := range statuses {
		if statuses[i].NodeUUID == state.NodeUUID {
			st = &statuses[i]
			break
		}
	}
	if st == nil {
		return SafeSpaceVerdict{LocalID: localID, Reason: "server has no record of node"},
			errors.New("server has no record of node")
	}
	if reason := reclaimIneligibleReason(*st, state.Blake3Hash); reason != "" {
		return SafeSpaceVerdict{LocalID: localID, Reason: reason}, errors.New(reason)
	}
	// Last gate, local: the recorded hash only speaks for the bytes the
	// item had at enqueue. Re-verify it is still those bytes (#229).
	if reason := localChangedReason(state, current); reason != "" {
		return SafeSpaceVerdict{LocalID: localID, Reason: reason}, errors.New(reason)
	}

	// All gates passed. Mark the local asset as offloaded.
	if err := e.q.SetMediaOffloaded(localID, true); err != nil {
		return SafeSpaceVerdict{LocalID: localID, Reason: "local flag set failed"},
			fmt.Errorf("%w: %v", ErrLocalFlagSetFailed, err)
	}
	return SafeSpaceVerdict{LocalID: localID, Eligible: true}, nil
}

// GetMediaStatus returns the backup status of a media item by localID.
func (e *Engine) GetMediaStatus(localID string) (string, error) {
	return e.q.GetMediaStatus(localID)
}

// GetAllMediaStatuses returns a map of localID/localPath/srcPathHash -> status for all tracked items.
func (e *Engine) GetAllMediaStatuses() (map[string]string, error) {
	return e.q.GetAllMediaStatuses()
}

// CountPendingUploads returns the number of pending/in-progress uploads in the queue.
func (e *Engine) CountPendingUploads() (int64, error) {
	return e.q.CountPendingUploads()
}

// ResetFailedUploads resets all FAILED upload items back to PENDING.
func (e *Engine) ResetFailedUploads() (int64, error) {
	return e.q.ResetFailedUploads()
}
