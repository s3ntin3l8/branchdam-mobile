package branchdam

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"
)

// gomobile-compatible binding layer. gomobile's gobind skips methods that
// accept or return struct types; these wrappers use only primitives so
// they survive into the Java/Swift bindings. A package-level mutex
// serializes access — the mobile shell is single-threaded by design
// (see EngineHolder.kt's executor).

var (
	bindingMu     sync.Mutex
	bindingEngine *Engine
)

func getBindingEngine() (*Engine, error) {
	bindingMu.Lock()
	defer bindingMu.Unlock()
	if bindingEngine == nil {
		return nil, fmt.Errorf("engine not open")
	}
	return bindingEngine, nil
}

// BindingOpen initialises the engine from primitive parameters.
// devCleartextHosts is a comma-separated list of hosts allowed over
// HTTP in debug builds; pass "" in production to require HTTPS.
func BindingOpen(dbPath, baseURL, apiKey, agentID, clientVersion, devCleartextHosts string) error {
	bindingMu.Lock()
	defer bindingMu.Unlock()

	if bindingEngine != nil {
		_ = bindingEngine.Close()
		bindingEngine = nil
	}

	var hosts []string
	if devCleartextHosts != "" {
		for _, h := range strings.Split(devCleartextHosts, ",") {
			h = strings.TrimSpace(h)
			if h != "" {
				hosts = append(hosts, h)
			}
		}
	}

	e, err := NewEngine(EngineOptions{
		DBPath:            dbPath,
		BaseURL:           baseURL,
		APIKey:            apiKey,
		AgentID:           agentID,
		ClientVersion:     clientVersion,
		DevCleartextHosts: hosts,
	})
	if err != nil {
		return err
	}
	bindingEngine = e
	return nil
}

// BindingClose closes the engine and releases resources.
func BindingClose() error {
	bindingMu.Lock()
	defer bindingMu.Unlock()
	if bindingEngine == nil {
		return nil
	}
	err := bindingEngine.Close()
	bindingEngine = nil
	return err
}

// BindingEnqueueMedia enqueues a local media file for upload without an explicit source path hash.
// Returns the upload ID (>= 1) on success or 0 on error.
func BindingEnqueueMedia(localPath, filename, localID, cameraModel string,
	capturedAtUnix, sizeBytes int64) (int64, error) {
	return BindingEnqueueMediaWithSourceHash(localPath, filename, localID, cameraModel, "", capturedAtUnix, sizeBytes)
}

// BindingEnqueueMediaWithSourceHash enqueues a local media file for upload with an explicit sourcePathHash.
// Returns the upload ID (>= 1) on success or 0 on error.
func BindingEnqueueMediaWithSourceHash(localPath, filename, localID, cameraModel, sourcePathHash string,
	capturedAtUnix, sizeBytes int64) (int64, error) {

	e, err := getBindingEngine()
	if err != nil {
		return 0, err
	}
	return e.EnqueueMedia(EnqueueMediaOptions{
		LocalPath:      localPath,
		Filename:       filename,
		LocalID:        localID,
		CameraModel:    cameraModel,
		SourcePathHash: sourcePathHash,
		CapturedAtUnix: capturedAtUnix,
		SizeBytes:      sizeBytes,
	})
}

// BindingEnqueueLineageEvent enqueues a lineage relationship event between
// two local media items, identified by the local IDs they were enqueued for
// upload under. Only exact (1.00) pairs are accepted; a lower confidence is
// REVIEW_REQUIRED and goes through BindingConfirmLineageEdge instead. See
// Engine.EnqueueLineageEvent.
func BindingEnqueueLineageEvent(parentLocalID, childLocalID,
	relationshipType, resolver string, confidence float64) (string, error) {

	e, err := getBindingEngine()
	if err != nil {
		return "", err
	}
	return e.EnqueueLineageEvent(
		parentLocalID, childLocalID, relationshipType, resolver,
		Confidence(confidence))
}

// BindingConfirmLineageEdge records the user's confirmation of a proposed
// lineage pair and queues its edge at the given confidence (never raised to
// 1.00); see Engine.ConfirmLineageEdge. Returns the event UUID.
func BindingConfirmLineageEdge(parentLocalID, childLocalID,
	relationshipType, resolver string, confidence float64) (string, error) {

	e, err := getBindingEngine()
	if err != nil {
		return "", err
	}
	return e.ConfirmLineageEdge(parentLocalID, childLocalID, relationshipType, resolver, Confidence(confidence))
}

// BindingRejectLineageEdge persists the user's rejection of a proposed
// lineage pair; see Engine.RejectLineageEdge.
func BindingRejectLineageEdge(parentLocalID, childLocalID,
	relationshipType, resolver string, confidence float64) error {

	e, err := getBindingEngine()
	if err != nil {
		return err
	}
	return e.RejectLineageEdge(parentLocalID, childLocalID, relationshipType, resolver, Confidence(confidence))
}

// BindingListLineageReviews returns the user's lineage review decisions as
// a JSON array; see Engine.LineageReviewsJSON.
func BindingListLineageReviews() (string, error) {
	e, err := getBindingEngine()
	if err != nil {
		return "[]", err
	}
	return e.LineageReviewsJSON()
}

// BindingEnqueueDeleteEvent enqueues a local-delete lifecycle event for the
// local ID the media was enqueued for upload under (not a node UUID); see
// Engine.EnqueueDeleteEvent.
func BindingEnqueueDeleteEvent(localID string) (string, error) {
	e, err := getBindingEngine()
	if err != nil {
		return "", err
	}
	return e.EnqueueDeleteEvent(localID)
}

// BindingSyncBatch runs a sync cycle with the default retry policy.
// Returns "uploaded,eventsSent" string on success.
func BindingSyncBatch(timeoutSecs, batchSize int64) (string, error) {
	return BindingSyncBatchWithRetry(timeoutSecs, batchSize, 0, 0)
}

// BindingSyncBatchWithRetry is BindingSyncBatch with the retry policy
// spelled out: retryBackoffSecs is the base of the exponential backoff
// after a failed attempt and maxRetries the number of failed attempts
// before an item is FAILED (0 = the defaults, 10s and 5). See SyncOptions.
func BindingSyncBatchWithRetry(timeoutSecs, batchSize, retryBackoffSecs, maxRetries int64) (string, error) {
	e, err := getBindingEngine()
	if err != nil {
		return "0,0", err
	}
	res, syncErr := e.SyncBatch(SyncOptions{
		TimeoutSecs:      int(timeoutSecs),
		BatchSize:        int(batchSize),
		RetryBackoffSecs: retryBackoffSecs,
		MaxRetries:       int(maxRetries),
		IncludeEvents:    true,
		IncludeUploads:   true,
	})
	if syncErr != nil {
		return "0,0", syncErr
	}
	return fmt.Sprintf("%d,%d", res.Uploaded, res.EventsSent), nil
}

// BindingIsMediaOffloaded returns the offload flag. On error returns false
// (fail-closed: shell refuses to delete).
func BindingIsMediaOffloaded(localID string) (bool, error) {
	e, err := getBindingEngine()
	if err != nil {
		return false, err
	}
	return e.IsMediaOffloaded(localID)
}

// BindingSetMediaOffloaded sets the offload flag directly.
func BindingSetMediaOffloaded(localID string, isOffloaded bool) error {
	e, err := getBindingEngine()
	if err != nil {
		return err
	}
	return e.SetMediaOffloaded(localID, isOffloaded)
}

// BindingSetCancelFlag requests cancellation of the current sync batch.
func BindingSetCancelFlag() error {
	e, err := getBindingEngine()
	if err != nil {
		return err
	}
	e.SetCancelFlag()
	return nil
}

// BindingFetchNamingTemplate fetches the naming template from the server.
func BindingFetchNamingTemplate() (string, error) {
	e, err := getBindingEngine()
	if err != nil {
		return "", err
	}
	return e.FetchNamingTemplate()
}

// BindingReclaimSafeSpace runs the atomic reclaim for a single local ID.
func BindingReclaimSafeSpace(localID string) error {
	e, err := getBindingEngine()
	if err != nil {
		return err
	}
	_, reclaimErr := e.ReclaimSafeSpace(localID)
	return reclaimErr
}

// BindingCheckSafeSpaceCandidates checks a batch of local IDs for
// eligibility. localIDs is a comma-separated list. Returns a JSON-encoded
// string array of SafeSpaceVerdict objects.
func BindingCheckSafeSpaceCandidates(localIDs string) (string, error) {
	e, err := getBindingEngine()
	if err != nil {
		return "", err
	}
	if localIDs == "" {
		return "[]", nil
	}
	ids := splitIDs(localIDs)
	candidates := make([]SafeSpaceCandidate, len(ids))
	for i, id := range ids {
		candidates[i] = SafeSpaceCandidate{LocalID: id}
	}
	verdicts, checkErr := e.CheckSafeSpaceCandidates(candidates)
	if checkErr != nil {
		return "", checkErr
	}
	b, err := json.Marshal(verdicts)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

func splitIDs(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

// BindingComputeHashes streams localPath and returns its 64-hex-char BLAKE3-256
// digest. Used by the OTG ingest post-copy verification (T2-7) so the shell can
// detect a corrupt SD card mid-copy without keeping a second BLAKE3
// implementation in sync with the upload-side hash. Returns "" on error;
// callers should treat that as "hash unavailable, defer to upload-side".
func BindingComputeHashes(localPath string) (string, error) {
	e, err := getBindingEngine()
	if err != nil {
		return "", err
	}
	if localPath == "" {
		return "", fmt.Errorf("local path is required")
	}
	hashes, hashErr := e.ComputeHashes(localPath, nil)
	if hashErr != nil {
		return "", hashErr
	}
	return hashes.Blake3, nil
}

// BindingLookupBlake3ForLocalID returns the BLAKE3-256 hash most recently
// recorded against localID in the local_media_state table, or "" if the
// localID is unknown. Used by the OTG ingest pipeline to detect the case
// where the same localID has been ingested before and produced a different
// hash — typically a sign that the source SD card is failing and the bytes
// have changed since the previous scan. The shell logs a warning when this
// happens but continues with the new hash (the source-of-truth is the file
// on disk, not the prior queue entry).
func BindingLookupBlake3ForLocalID(localID string) (string, error) {
	e, err := getBindingEngine()
	if err != nil {
		return "", err
	}
	if localID == "" {
		return "", nil
	}
	state, lookupErr := e.queue.GetMediaByLocalID(localID)
	if lookupErr != nil {
		// sql.ErrNoRows is the "no prior ingest" case; not an error.
		if strings.Contains(lookupErr.Error(), "sql: no rows in result set") {
			return "", nil
		}
		return "", lookupErr
	}
	return state.Blake3Hash, nil
}

// BindingCheckContent calls GET /api/v1/agent/check-content with the given
// hashes. Returns a JSON-encoded ContentCheckResult string, or "" on error.
// Follows the BindingCheckSafeSpaceCandidates pattern for gomobile compatibility.
// Uses a 30-second timeout to prevent ANR on mobile if the server is slow.
func BindingCheckContent(fastHash, fullHash string) (string, error) {
	e, err := getBindingEngine()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	result, checkErr := e.client.CheckContent(ctx, fastHash, fullHash)
	if checkErr != nil {
		return "", checkErr
	}
	b, err := json.Marshal(result)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// BindingGetMediaStatus returns the status of a local media asset.
// Returns "OFFLOADED", "COMPLETED", "IN_PROGRESS", "PENDING", "FAILED", or "NOT_ENQUEUED".
func BindingGetMediaStatus(localID string) (string, error) {
	e, err := getBindingEngine()
	if err != nil {
		return "NOT_ENQUEUED", err
	}
	return e.GetMediaStatus(localID)
}

// BindingGetAllMediaStatuses returns a JSON-encoded map of localID/localPath/srcPathHash -> status.
func BindingGetAllMediaStatuses() (string, error) {
	e, err := getBindingEngine()
	if err != nil {
		return "{}", err
	}
	m, statusErr := e.GetAllMediaStatuses()
	if statusErr != nil {
		return "{}", statusErr
	}
	b, err := json.Marshal(m)
	if err != nil {
		return "{}", err
	}
	return string(b), nil
}

// BindingCountPendingUploads returns the number of pending/in-progress uploads in the queue.
func BindingCountPendingUploads() (int64, error) {
	e, err := getBindingEngine()
	if err != nil {
		return 0, err
	}
	return e.CountPendingUploads()
}

// BindingResetFailedUploads resets all FAILED upload items back to PENDING.
func BindingResetFailedUploads() (int64, error) {
	e, err := getBindingEngine()
	if err != nil {
		return 0, err
	}
	return e.ResetFailedUploads()
}

// BindingGetActiveUploadProgress returns JSON string of active upload metrics, or "" if none.
func BindingGetActiveUploadProgress() (string, error) {
	e, err := getBindingEngine()
	if err != nil {
		return "", err
	}
	prog, progErr := e.GetActiveUploadProgress()
	if progErr != nil || prog == nil {
		return "", progErr
	}
	b, err := json.Marshal(prog)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// BindingStagePendingImport persists a camera-roll item awaiting the
// user's import confirmation; see Engine.StagePendingImport. Returns false
// (without error) when the user already skipped the item.
func BindingStagePendingImport(localID, localPath, filename, mimeType string, sizeBytes, capturedAtUnix int64) (bool, error) {
	e, err := getBindingEngine()
	if err != nil {
		return false, err
	}
	return e.StagePendingImport(localID, localPath, filename, mimeType, sizeBytes, capturedAtUnix)
}

// BindingListPendingImports returns the items awaiting an import decision
// as a JSON array; see Engine.ListPendingImportsJSON. limit <= 0 returns
// all of them.
func BindingListPendingImports(limit int64) (string, error) {
	e, err := getBindingEngine()
	if err != nil {
		return "[]", err
	}
	return e.ListPendingImportsJSON(int(limit))
}

// BindingDeletePendingImport forgets a pending or skipped item; see
// Engine.DeletePendingImport.
func BindingDeletePendingImport(localID string) (bool, error) {
	e, err := getBindingEngine()
	if err != nil {
		return false, err
	}
	return e.DeletePendingImport(localID)
}

// BindingSuppressPendingImport records that the user skipped localID; see
// Engine.SuppressPendingImport.
func BindingSuppressPendingImport(localID string) error {
	e, err := getBindingEngine()
	if err != nil {
		return err
	}
	return e.SuppressPendingImport(localID)
}

// BindingIsImportSuppressed reports whether the user skipped localID. On
// error returns false.
func BindingIsImportSuppressed(localID string) (bool, error) {
	e, err := getBindingEngine()
	if err != nil {
		return false, err
	}
	return e.IsImportSuppressed(localID)
}
