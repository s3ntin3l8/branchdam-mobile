package engine

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
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

// RequestCancel sets the in-process cancel flag. The next
// SyncUploads / SyncEvents call observes it and returns the
// partial-sync counts; subsequent syncs are unaffected.
func (e *Engine) RequestCancel() {
	e.cancelRequested.Store(true)
}

type SafeSpaceCandidate struct {
	LocalID    string `json:"localId"`
	NodeUUID   string `json:"nodeUuid"`
	Blake3Hash string `json:"blake3Hash"`
	IsVerified bool   `json:"isVerified"`
	IsEligible bool   `json:"isEligible"`
	Tier       string `json:"tier"`
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
func (e *Engine) EnqueueLocalCapture(localPath, filename string, capturedAtUnix int64, localID string, cameraModel, sourcePathHash string) (*queue.UploadItem, error) {
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

	fastHash, fullHash, sizeBytes, err := hasher.HashReader(file)
	if err != nil {
		return nil, &client.ClientError{
			Code:    client.CodeIOError,
			Message: fmt.Sprintf("hash local file: %v", err),
			Cause:   err,
		}
	}

	// Reset any previous FAILED entry for this file so re-enqueueing recovers it
	_ = e.q.ResetUploadByBlake3Hash(fullHash, localPath, sizeBytes)

	// Dedup gate: check if this blake3Hash is already queued or uploaded
	if existing, err := e.q.GetUploadItemByBlake3Hash(fullHash); err == nil && existing != nil {
		if localID != "" {
			_ = e.q.RecordLocalMedia(localID, existing.NodeUUID, fullHash, "ACTIVE")
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
	}

	id, err := e.q.EnqueueUpload(item)
	if err != nil {
		return nil, fmt.Errorf("failed to enqueue upload: %w", err)
	}
	item.ID = id

	if localID != "" {
		_ = e.q.RecordLocalMedia(localID, "", fullHash, "ACTIVE")
	}

	return item, nil
}

// SyncUploads processes a batch of pending uploads and streams them to the server.
func (e *Engine) SyncUploads(ctx context.Context, batchSize int) (int, error) {
	if batchSize <= 0 {
		batchSize = 5
	}

	// B.2.2: capture + reset the cancel flag at the start of each
	// SyncBatch. The flag is in-process (atomic.Bool), so a single
	// SetCancelFlag only affects the in-flight sync. The shell can
	// safely re-sync after a cancel without being stuck.
	cancelled := e.cancelRequested.Swap(false)

	if cancelled {
		slog.Info("engine: sync cancelled before claim")
		return 0, ctx.Err()
	}

	items, err := e.q.ClaimPendingUploads(batchSize, 10, 5)
	if err != nil {
		return 0, fmt.Errorf("claim uploads failed: %w", err)
	}

	defer e.clearActiveUpload()

	completedCount := 0
	for i, item := range items {
		// B.2.2: also check the flag between items so a
		// SetCancelFlag mid-batch halts gracefully.
		if e.cancelRequested.Load() {
			return completedCount, ctx.Err()
		}
		if ctx.Err() != nil {
			return completedCount, ctx.Err()
		}

		e.setActiveUpload(item.ID, item.TargetFilename, item.SizeBytes, i+1, len(items))

		// Background pre-screen: check if server already has this content by hash before streaming file payload
		if e.c != nil {
			checkRes, checkErr := e.c.CheckContent(ctx, item.FastHash, item.Blake3Hash)
			if checkErr == nil && checkRes.Found && checkRes.NodeUUID != "" {
				slog.Info("engine: background sync pre-screen dedup — content already exists on server",
					"nodeUUID", checkRes.NodeUUID, "localPath", item.LocalPath)
				_ = e.q.MarkUploadComplete(item.ID, checkRes.NodeUUID)
				if err := e.q.UpdateLocalMediaNodeUUID(item.Blake3Hash, checkRes.NodeUUID); err != nil {
					slog.Warn("engine: failed to update local media state nodeUUID", "blake3", item.Blake3Hash, "err", err)
				}
				completedCount++
				e.clearActiveUpload()
				continue
			}
		}

		completed, _ := e.uploadPendingItem(ctx, item)
		if completed {
			completedCount++
		}
		e.clearActiveUpload()
	}

	return completedCount, nil
}

// uploadPendingItem attempts to open, hash-check, and stream upload a single pending item.
// Uses a helper method so defer file.Close() runs cleanly per-item.
func (e *Engine) uploadPendingItem(ctx context.Context, item *queue.UploadItem) (completed bool, err error) {
	// B.2.5: Stat before Open so a missing file surfaces as IO_ERROR.
	if _, statErr := os.Stat(item.LocalPath); statErr != nil {
		_ = e.q.MarkUploadFailed(item.ID, fmt.Sprintf("file stat failed: %v", statErr), 5)
		return false, statErr
	}

	file, openErr := os.Open(item.LocalPath)
	if openErr != nil {
		_ = e.q.MarkUploadFailed(item.ID, fmt.Sprintf("file open failed: %v", openErr), 5)
		return false, openErr
	}
	// B.2.5: defer close inside helper method so cleanup is guaranteed.
	defer file.Close()

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

	resp, uploadErr := e.c.UploadStream(ctx, file, item.SizeBytes, item.TargetFilename, uploadOpts)
	if uploadErr != nil {
		// B.2.6: handle the new structured dedup / hash-mismatch
		// codes by surfacing them as typed errors; the engine
		// distinguishes "soft dedup, mark complete" from "hard
		// dedup failure, re-queue" via the Code.
		var ce *client.ClientError
		if errors.As(uploadErr, &ce) {
			switch ce.Code {
			case client.CodeDedupNoNodeUUID, client.CodeHashMismatch, client.CodeResponseTooLarge:
				// Hard failure; mark as failed so it retries.
				_ = e.q.MarkUploadFailed(item.ID, uploadErr.Error(), 5)
				return false, uploadErr
			}
		}
		if dedupResp, ok := client.AsDedupResponse(uploadErr); ok {
			slog.Info("engine: upload dedup — server returned existing node",
				"existingUUID", dedupResp.NodeUUID, "localPath", item.LocalPath)
			_ = e.q.MarkUploadComplete(item.ID, dedupResp.NodeUUID)
			return true, nil
		}
		_ = e.q.MarkUploadFailed(item.ID, uploadErr.Error(), 5)
		return false, uploadErr
	}

	if resp.IsDedup {
		slog.Info("engine: upload dedup — server acknowledged existing content via X-Dedup",
			"existingUUID", resp.NodeUUID, "localPath", item.LocalPath)
	}

	if err := e.q.MarkUploadComplete(item.ID, resp.NodeUUID); err != nil {
		return false, err
	}
	if err := e.q.UpdateLocalMediaNodeUUID(item.Blake3Hash, resp.NodeUUID); err != nil {
		slog.Warn("engine: failed to update local media state nodeUUID", "blake3", item.Blake3Hash, "err", err)
	}

	return true, nil
}

// SyncEvents dispatches pending lifecycle events to the central branchDAM server.
func (e *Engine) SyncEvents(ctx context.Context, batchSize int) (int, error) {
	if batchSize <= 0 {
		batchSize = 10
	}

	// B.2.2: see SyncUploads.
	cancelled := e.cancelRequested.Swap(false)

	if cancelled {
		slog.Info("engine: event sync cancelled before claim")
		return 0, ctx.Err()
	}

	events, err := e.q.ClaimPendingEvents(batchSize, 10, 5)
	if err != nil {
		return 0, fmt.Errorf("claim events failed: %w", err)
	}

	sentCount := 0
	for _, evt := range events {
		if e.cancelRequested.Load() {
			return sentCount, ctx.Err()
		}
		if ctx.Err() != nil {
			return sentCount, ctx.Err()
		}

		_, err := e.c.SubmitEvent(ctx, evt.EventUUID, evt.EventType, evt.PayloadJSON)
		if err != nil {
			_ = e.q.MarkEventFailed(evt.ID, err.Error(), 5)
			continue
		}

		if err := e.q.MarkEventSent(evt.ID); err != nil {
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
			}
			continue
		}

		candidateMap[localID] = SafeSpaceCandidate{
			LocalID:    localID,
			NodeUUID:   state.NodeUUID,
			Blake3Hash: state.Blake3Hash,
			IsVerified: false,
			IsEligible: false,
		}
		queryUUIDs = append(queryUUIDs, state.NodeUUID)
		idToLocal[state.NodeUUID] = append(idToLocal[state.NodeUUID], localID)
	}

	if len(queryUUIDs) > 0 {
		statuses, err := e.c.GetNodeStatuses(ctx, queryUUIDs)
		if err != nil {
			return nil, fmt.Errorf("failed to query node statuses: %w", err)
		}

		for _, st := range statuses {
			localIDsForNode, exists := idToLocal[st.NodeUUID]
			if !exists {
				continue
			}

			isEligible := st.Found && st.Verified && (st.Tier == "TIER3_MASTER_ARCHIVE" || st.Tier == "TIER2_DERIVATIVE_CACHE")
			for _, localID := range localIDsForNode {
				candidateMap[localID] = SafeSpaceCandidate{
					LocalID:    localID,
					NodeUUID:   st.NodeUUID,
					Blake3Hash: candidateMap[localID].Blake3Hash,
					IsVerified: st.Verified,
					IsEligible: isEligible,
					Tier:       st.Tier,
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
// reclaim — the server is re-queried to confirm the current verified +
// tier state, and the local flag is set only inside the same logical
// operation. If the server says "not eligible", the flag is not set
// and the verdict's Reason explains why.
//
// Returns SafeSpaceVerdict with Eligible=true on success; callers
// (the branchdam FFI surface) should only delete the local file
// after seeing Eligible=true.
func (e *Engine) SafeSpaceReclaim(ctx context.Context, localID string) (SafeSpaceVerdict, error) {
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
	if len(statuses) == 0 {
		return SafeSpaceVerdict{LocalID: localID, Reason: "server has no record of node"},
			errors.New("server has no record of node")
	}
	st := statuses[0]
	if !st.Found {
		return SafeSpaceVerdict{LocalID: localID, Reason: "node not found on server"},
			errors.New("node not found on server")
	}
	if !st.Verified {
		return SafeSpaceVerdict{LocalID: localID, Reason: "not verified on server"},
			errors.New("not verified on server")
	}
	if st.Tier != "TIER3_MASTER_ARCHIVE" && st.Tier != "TIER2_DERIVATIVE_CACHE" {
		return SafeSpaceVerdict{LocalID: localID, Reason: "tier ineligible: " + st.Tier},
			errors.New("tier ineligible: " + st.Tier)
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
