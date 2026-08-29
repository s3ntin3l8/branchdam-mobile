package engine

import (
	"context"
	"fmt"
	"log/slog"
	"os"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
	"github.com/s3ntin3l8/branchdam-mobile/core/hasher"
	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

type Engine struct {
	q *queue.Queue
	c *client.Client
}

type SafeSpaceCandidate struct {
	LocalID    string `json:"localId"`
	NodeUUID   string `json:"nodeUuid"`
	Blake3Hash string `json:"blake3Hash"`
	IsVerified bool   `json:"isVerified"`
	IsEligible bool   `json:"isEligible"`
	Tier       string `json:"tier"`
}

func New(q *queue.Queue, c *client.Client) *Engine {
	return &Engine{
		q: q,
		c: c,
	}
}

// EnqueueLocalCapture reads a local media file, calculates hashes, records local state, and queues for upload.
func (e *Engine) EnqueueLocalCapture(localPath, filename string, capturedAtUnix int64, localID string, cameraModel ...string) (*queue.UploadItem, error) {
	file, err := os.Open(localPath)
	if err != nil {
		return nil, fmt.Errorf("failed to open local file: %w", err)
	}
	defer file.Close()

	fastHash, fullHash, sizeBytes, err := hasher.HashReader(file)
	if err != nil {
		return nil, fmt.Errorf("failed to hash local file: %w", err)
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

	items, err := e.q.ClaimPendingUploads(batchSize, 10, 5)
	if err != nil {
		return 0, fmt.Errorf("claim uploads failed: %w", err)
	}

	completedCount := 0
	for _, item := range items {
		if ctx.Err() != nil {
			return completedCount, ctx.Err()
		}

		if err := e.q.MarkUploadInProgress(item.ID); err != nil {
			continue
		}

		file, err := os.Open(item.LocalPath)
		if err != nil {
			_ = e.q.MarkUploadFailed(item.ID, fmt.Sprintf("file open failed: %v", err), 5)
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

		resp, err := e.c.UploadStream(ctx, file, item.SizeBytes, item.TargetFilename, uploadOpts)
		file.Close()

		if err != nil {
			if dedupResp, ok := client.AsDedupResponse(err); ok {
				slog.Info("engine: upload dedup — server returned existing node",
					"existingUUID", dedupResp.NodeUUID, "localPath", item.LocalPath)
				_ = e.q.MarkUploadComplete(item.ID, dedupResp.NodeUUID)
				completedCount++
				continue
			}
			_ = e.q.MarkUploadFailed(item.ID, err.Error(), 5)
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

	events, err := e.q.ClaimPendingEvents(batchSize, 10, 5)
	if err != nil {
		return 0, fmt.Errorf("claim events failed: %w", err)
	}

	sentCount := 0
	for _, evt := range events {
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

// SafeSpaceReclaim marks an asset as intentionally offloaded to suppress trashing deletion events.
func (e *Engine) SafeSpaceReclaim(localID string) error {
	return e.q.SetMediaOffloaded(localID, true)
}
