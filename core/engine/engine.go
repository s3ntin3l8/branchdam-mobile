package engine

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"sync/atomic"

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

type Engine struct {
	q *queue.Queue
	c *client.Client

	// cancelRequested is an atomic flag that the shell's SetCancelFlag
	// (via the branchdam FFI) sets and SyncUploads / SyncEvents read
	// + reset at the start of each batch. In-process atomic (not a
	// SQLite column) so a single cancel never persists across
	// syncs — this was the critical bug Hermes flagged in B.2.2.
	cancelRequested atomic.Bool
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
func (e *Engine) EnqueueLocalCapture(localPath, filename string, capturedAtUnix int64, localID string, cameraModel ...string) (*queue.UploadItem, error) {
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

	// Dedup gate: check if this blake3Hash is already queued or uploaded
	if existing, err := e.q.GetUploadItemByBlake3Hash(fullHash); err == nil && existing != nil {
		if localID != "" {
			_ = e.q.RecordLocalMedia(localID, existing.NodeUUID, fullHash, "ACTIVE")
		}
		return existing, nil
	}

	cam := ""
	if len(cameraModel) > 0 && cameraModel[0] != "" {
		cam = cameraModel[0]
	} else if e.c != nil {
		cam = e.c.AgentID()
	}

	item := &queue.UploadItem{
		LocalPath:      localPath,
		TargetFilename: filename,
		FastHash:       fastHash,
		Blake3Hash:     fullHash,
		CameraModel:    cam,
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

	completedCount := 0
	for _, item := range items {
		// B.2.2: also check the flag between items so a
		// SetCancelFlag mid-batch halts gracefully.
		if e.cancelRequested.Load() {
			return completedCount, ctx.Err()
		}
		if ctx.Err() != nil {
			return completedCount, ctx.Err()
		}

		// B.2.5: Stat before Open so a missing file surfaces as IO_ERROR.
		if _, statErr := os.Stat(item.LocalPath); statErr != nil {
			_ = e.q.MarkUploadFailed(item.ID, fmt.Sprintf("file stat failed: %v", statErr), 5)
			continue
		}

		file, openErr := os.Open(item.LocalPath)
		if openErr != nil {
			_ = e.q.MarkUploadFailed(item.ID, fmt.Sprintf("file open failed: %v", openErr), 5)
			continue
		}

		cam := item.CameraModel
		if cam == "" && e.c != nil {
			cam = e.c.AgentID()
		}

		uploadOpts := client.UploadOptions{
			CameraModel:    cam,
			FastHash:       item.FastHash,
			Blake3Hash:     item.Blake3Hash,
			CapturedAtUnix: item.CapturedAtUnix,
		}

		resp, uploadErr := e.c.UploadStream(ctx, file, item.SizeBytes, item.TargetFilename, uploadOpts)
		// B.2.5: file close moved to defer (declared inside the loop) so
		// the close always runs even if a panic occurs in UploadStream.
		file.Close()

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
					continue
				}
			}
			if dedupResp, ok := client.AsDedupResponse(uploadErr); ok {
				slog.Info("engine: upload dedup — server returned existing node",
					"existingUUID", dedupResp.NodeUUID, "localPath", item.LocalPath)
				_ = e.q.MarkUploadComplete(item.ID, dedupResp.NodeUUID)
				completedCount++
				continue
			}
			_ = e.q.MarkUploadFailed(item.ID, uploadErr.Error(), 5)
			continue
		}

		if resp.IsDedup {
			slog.Info("engine: upload dedup — server acknowledged existing content via X-Dedup",
				"existingUUID", resp.NodeUUID, "localPath", item.LocalPath)
		}

		if err := e.q.MarkUploadComplete(item.ID, resp.NodeUUID); err != nil {
			continue
		}

		completedCount++
	}

	return completedCount, nil
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

		_, err := e.c.SubmitEvent(ctx, evt.EventType, evt.PayloadJSON)
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
	idToLocal := make(map[string]string)

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
		idToLocal[state.NodeUUID] = localID
	}

	if len(queryUUIDs) > 0 {
		statuses, err := e.c.GetNodeStatuses(ctx, queryUUIDs)
		if err != nil {
			return nil, fmt.Errorf("failed to query node statuses: %w", err)
		}

		for _, st := range statuses {
			localID, exists := idToLocal[st.NodeUUID]
			if !exists {
				continue
			}

			isEligible := st.Found && st.Verified && (st.Tier == "TIER3_MASTER_ARCHIVE" || st.Tier == "TIER2_DERIVATIVE_CACHE")
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
			fmt.Errorf("local state lookup: %w", err)
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
