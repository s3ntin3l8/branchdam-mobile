package engine

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

func TestEngineFullLifecycle(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "engine_test.db")
	q, err := queue.Open(dbPath)
	if err != nil {
		t.Fatalf("failed to open queue: %v", err)
	}
	defer q.Close()

	// Mock server
	var uploadedFiles []string
	var submittedEvents []string

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/agent/upload":
			filename := r.Header.Get("X-Filename")
			uploadedFiles = append(uploadedFiles, filename)
			_, _ = io.ReadAll(r.Body)
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(client.UploadResponse{
				OK:           true,
				NodeUUID:     "018f-test-node-1",
				FilePath:     "/storage/archive/2026/2026-08-29/" + filename,
				Status:       "UPLOADED",
				RelativePath: "2026/2026-08-29/" + filename,
			})
		case "/api/v1/agent/events":
			var req client.AgentEventRequest
			_ = json.NewDecoder(r.Body).Decode(&req)
			submittedEvents = append(submittedEvents, req.EventType)
			_ = json.NewEncoder(w).Encode(client.AgentEventResponse{EventID: "evt-01"})
		case "/api/v1/agent/node-status":
			_ = json.NewEncoder(w).Encode(client.NodeStatusResponse{
				Statuses: []client.NodeStatusItem{
					{
						NodeUUID: "018f-test-node-1",
						Found:    true,
						Tier:     "TIER3_MASTER_ARCHIVE",
						Verified: true,
					},
				},
			})
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	c := client.New(client.Config{
		BaseURL: server.URL,
		APIKey:  "test-key",
		AgentID: "pixel-test",
	})

	eng := New(q, c)

	// Create test file
	testFilePath := filepath.Join(tempDir, "PXL_20260829_001.dng")
	testData := []byte("computational raw test data payload")
	if err := os.WriteFile(testFilePath, testData, 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}

	// 1. Enqueue capture
	item, err := eng.EnqueueLocalCapture(testFilePath, "PXL_20260829_001.dng", 1724000000, "local_uri_1")
	if err != nil {
		t.Fatalf("EnqueueLocalCapture failed: %v", err)
	}
	if item.Blake3Hash == "" || item.FastHash == "" {
		t.Fatalf("missing hashes in item: %+v", item)
	}

	// 2. Sync uploads
	uploadedCount, err := eng.SyncUploads(context.Background(), 10)
	if err != nil {
		t.Fatalf("SyncUploads failed: %v", err)
	}
	if uploadedCount != 1 || len(uploadedFiles) != 1 {
		t.Fatalf("expected 1 upload, got %d (server saw %d)", uploadedCount, len(uploadedFiles))
	}

	// 3. Enqueue and sync event
	_, err = q.EnqueueEvent("EVENT_EDGE_ATTACHED", `{"child":"018f-test-node-1"}`)
	if err != nil {
		t.Fatalf("EnqueueEvent failed: %v", err)
	}

	sentEvents, err := eng.SyncEvents(context.Background(), 10)
	if err != nil {
		t.Fatalf("SyncEvents failed: %v", err)
	}
	if sentEvents != 1 || len(submittedEvents) != 1 {
		t.Fatalf("expected 1 sent event, got %d", sentEvents)
	}

	// 4. Check Safe Space candidates
	_ = q.RecordLocalMedia("local_uri_1", "018f-test-node-1", item.Blake3Hash, "ACTIVE")
	candidates, err := eng.CheckSafeSpaceCandidates(context.Background(), []string{"local_uri_1"})
	if err != nil {
		t.Fatalf("CheckSafeSpaceCandidates failed: %v", err)
	}
	if len(candidates) != 1 || !candidates[0].IsEligible || !candidates[0].IsVerified {
		t.Fatalf("expected eligible verified candidate, got: %+v", candidates)
	}

	// 5. Reclaim safe space
	if _, err := eng.SafeSpaceReclaim(context.Background(), "local_uri_1"); err != nil {
		t.Fatalf("SafeSpaceReclaim failed: %v", err)
	}

	isOffloaded, err := q.IsMediaOffloaded("local_uri_1")
	if err != nil || !isOffloaded {
		t.Fatalf("expected media to be offloaded, got %v", isOffloaded)
	}
}

func TestEnqueueLocalCapture_Dedup(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "engine_dedup_test.db")
	q, err := queue.Open(dbPath)
	if err != nil {
		t.Fatalf("failed to open queue: %v", err)
	}
	defer q.Close()

	eng := New(q, nil)

	testFilePath := filepath.Join(tempDir, "PXL_DUPLICATE.dng")
	testData := []byte("identical payload across multiple scans")
	if err := os.WriteFile(testFilePath, testData, 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}

	// First enqueue
	item1, err := eng.EnqueueLocalCapture(testFilePath, "PXL_DUPLICATE.dng", 1724000000, "local_uri_dup_1")
	if err != nil {
		t.Fatalf("first EnqueueLocalCapture failed: %v", err)
	}

	// Verify count is 1
	count, err := q.CountPendingUploads()
	if err != nil || count != 1 {
		t.Fatalf("expected count 1, got %d", count)
	}

	// Second enqueue with same file content (same BLAKE3 hash)
	item2, err := eng.EnqueueLocalCapture(testFilePath, "PXL_DUPLICATE.dng", 1724000000, "local_uri_dup_2")
	if err != nil {
		t.Fatalf("second EnqueueLocalCapture failed: %v", err)
	}

	// Should return the exact same item ID and not create duplicate queue row
	if item1.ID != item2.ID {
		t.Fatalf("expected item IDs to match for duplicate hash: %d != %d", item1.ID, item2.ID)
	}

	countAfter, err := q.CountPendingUploads()
	if err != nil || countAfter != 1 {
		t.Fatalf("expected count to remain 1 after duplicate enqueue, got %d", countAfter)
	}
}

func TestSyncUploads_DedupResponse(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "engine_sync_dedup.db")
	q, err := queue.Open(dbPath)
	if err != nil {
		t.Fatalf("failed to open queue: %v", err)
	}
	defer q.Close()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Dedup", "true")
		_ = json.NewEncoder(w).Encode(client.UploadResponse{
			OK:       true,
			NodeUUID: "existing-server-node-uuid",
			Status:   "EXISTS",
		})
	}))
	defer server.Close()

	c := client.New(client.Config{BaseURL: server.URL, APIKey: "test", AgentID: "pixel"})
	eng := New(q, c)

	testFilePath := filepath.Join(tempDir, "PXL_SERVER_DUP.dng")
	if err := os.WriteFile(testFilePath, []byte("server dup bytes"), 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}

	item, err := eng.EnqueueLocalCapture(testFilePath, "PXL_SERVER_DUP.dng", 1724000000, "local_uri_srv")
	if err != nil {
		t.Fatalf("EnqueueLocalCapture failed: %v", err)
	}

	completedCount, err := eng.SyncUploads(context.Background(), 10)
	if err != nil {
		t.Fatalf("SyncUploads failed: %v", err)
	}
	if completedCount != 1 {
		t.Fatalf("expected 1 completed upload from server dedup, got %d", completedCount)
	}

	// Verify item in queue is marked completed with existing server node UUID
	claimed, err := q.ClaimPendingUploads(10, 0, 5)
	if err != nil {
		t.Fatalf("ClaimPendingUploads failed: %v", err)
	}
	if len(claimed) != 0 {
		t.Fatalf("expected 0 pending uploads after dedup complete, got %d", len(claimed))
	}

	state, err := q.GetUploadItemByBlake3Hash(item.Blake3Hash)
	if err != nil || state == nil {
		t.Fatalf("failed to get upload item: %v", err)
	}
	if state.Status != queue.UploadCompleted || state.NodeUUID != "existing-server-node-uuid" {
		t.Fatalf("unexpected state after server dedup: %+v", state)
	}
}

// TestEnqueueLocalCapture_FileMissing_SurfacesIOError: B.2.5 — a
// non-existent local file must produce a *client.ClientError with
// CodeIOError so the shell can map it to branchdam.Error{Code:
// "IO_ERROR"}.
func TestEnqueueLocalCapture_FileMissing_SurfacesIOError(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "engine_missing_test.db")
	q, err := queue.Open(dbPath)
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()

	eng := New(q, nil)

	missingPath := filepath.Join(tempDir, "does_not_exist.dng")
	_, err = eng.EnqueueLocalCapture(missingPath, "does_not_exist.dng", 1724000000, "local_uri_missing")
	if err == nil {
		t.Fatalf("expected error for missing file, got nil")
	}
	ce, ok := err.(*client.ClientError)
	if !ok {
		t.Fatalf("error type = %T, want *client.ClientError", err)
	}
	if ce.Code != client.CodeIOError {
		t.Fatalf("Code = %q, want %q", ce.Code, client.CodeIOError)
	}
}

// TestSyncUploads_CancelFlagHonored: B.2.2 — calling SetCancelFlag
// mid-batch causes SyncUploads to stop at the next per-item checkpoint.
// The shell's SetCancelFlag is the cancellation mechanism; SyncUploads
// returns 0 completed and no error (the cancel is a soft signal — the
// shell can re-invoke SyncUploads to resume).
func TestSyncUploads_CancelFlagHonored(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "engine_cancel_test.db")
	q, err := queue.Open(dbPath)
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()

	// Enqueue 3 items. No server needed because we cancel before the
	// first HTTP request.
	for i := 0; i < 3; i++ {
		_, err := q.EnqueueUpload(&queue.UploadItem{
			LocalPath:      "/sdcard/DCIM/file.dng",
			TargetFilename: "file.dng",
			Blake3Hash:     "b3cancel",
		})
		if err != nil {
			t.Fatalf("EnqueueUpload: %v", err)
		}
	}

	// Pre-set the in-process cancel flag; the engine's SyncUploads
	// swaps it back to false at the start of the batch, then observes
	// the captured value and returns 0 with no items claimed.
	eng := New(q, nil)
	eng.RequestCancel()
	count, err := eng.SyncUploads(context.Background(), 10)
	if err != nil {
		t.Fatalf("SyncUploads: %v", err)
	}
	if count != 0 {
		t.Fatalf("expected 0 completed before cancel, got %d", count)
	}
	// Items should still be PENDING (claim filtered by cancel flag).
	if pending, _ := q.CountPendingUploads(); pending != 3 {
		t.Fatalf("expected 3 pending, got %d (cancel should not have claimed anything)", pending)
	}
}

// TestSyncUploads_CancelDoesNotStallNextBatch: B.2.2 — after a
// SetCancelFlag, the next SyncBatch must reset the flag and process
// items normally. Without the reset, the cancel would be permanent
// and every subsequent SyncBatch would claim zero rows.
func TestSyncUploads_CancelDoesNotStallNextBatch(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "engine_resume_test.db")
	q, err := queue.Open(dbPath)
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()

	var uploads int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		uploads++
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(client.UploadResponse{
			OK:       true,
			NodeUUID: "resume-node-1",
			Status:   "UPLOADED",
		})
	}))
	defer server.Close()

	c := client.New(client.Config{BaseURL: server.URL, APIKey: "k", AgentID: "a"})
	eng := New(q, c)

	testFilePath := filepath.Join(tempDir, "PXL_RESUME.dng")
	if err := os.WriteFile(testFilePath, []byte("resume"), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	if _, err := eng.EnqueueLocalCapture(testFilePath, "PXL_RESUME.dng", 1724000000, "local_uri_resume"); err != nil {
		t.Fatalf("EnqueueLocalCapture: %v", err)
	}

	// 1) Cancel before the first sync.
	eng.RequestCancel()
	first, err := eng.SyncUploads(context.Background(), 10)
	if err != nil {
		t.Fatalf("first SyncUploads: %v", err)
	}
	if first != 0 {
		t.Fatalf("first SyncUploads: expected 0 (cancelled), got %d", first)
	}
	if uploads != 0 {
		t.Fatalf("server saw %d uploads after cancel, want 0", uploads)
	}

	// 2) Next SyncBatch — engine resets the flag and processes the
	// item. Without the reset, this would still be 0.
	second, err := eng.SyncUploads(context.Background(), 10)
	if err != nil {
		t.Fatalf("second SyncUploads: %v", err)
	}
	if second != 1 {
		t.Fatalf("second SyncUploads: expected 1 (resumed), got %d", second)
	}
	if uploads != 1 {
		t.Fatalf("server saw %d uploads after resume, want 1", uploads)
	}
}

// TestSyncUploads_DedupNoNodeUUID_HardFailure: B.2.6 — server
// returns 409 with no nodeUuid; the engine must NOT mark the item
// complete (the audit's "asset permanently orphaned" failure mode).
func TestSyncUploads_DedupNoNodeUUID_HardFailure(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "engine_dedup_empty_test.db")
	q, err := queue.Open(dbPath)
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"error":"duplicate"}`))
	}))
	defer server.Close()

	c := client.New(client.Config{BaseURL: server.URL, APIKey: "k", AgentID: "a"})
	eng := New(q, c)

	testFilePath := filepath.Join(tempDir, "PXL_DEDUP_EMPTY.dng")
	if err := os.WriteFile(testFilePath, []byte("payload"), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	item, err := eng.EnqueueLocalCapture(testFilePath, "PXL_DEDUP_EMPTY.dng", 1724000000, "local_uri_dup_empty")
	if err != nil {
		t.Fatalf("EnqueueLocalCapture: %v", err)
	}

	completed, err := eng.SyncUploads(context.Background(), 10)
	if err != nil {
		t.Fatalf("SyncUploads: %v", err)
	}
	if completed != 0 {
		t.Fatalf("expected 0 completed, got %d (item should NOT be marked complete with empty nodeUUID)", completed)
	}

	// Confirm the item is still PENDING (retry path), not COMPLETED.
	state, err := q.GetUploadItemByBlake3Hash(item.Blake3Hash)
	if err != nil || state == nil {
		t.Fatalf("GetUploadItemByBlake3Hash: %v", err)
	}
	if state.Status == queue.UploadCompleted {
		t.Fatalf("item is COMPLETED but 409 had no nodeUuid: %+v", state)
	}
}

// TestSafeSpaceReclaim_Ineligible: B.2.7 — the engine-owned atomic
// reclaim must NOT mark the asset offloaded when the server says the
// node isn't verified+TIER3/2. The audit's invariant is "set the flag
// only inside the same logical operation as the server confirmation";
// this test exercises the negative case.
func TestSafeSpaceReclaim_Ineligible(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "engine_reclaim_test.db")
	q, err := queue.Open(dbPath)
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(client.NodeStatusResponse{
			Statuses: []client.NodeStatusItem{
				{
					NodeUUID: "node-1",
					Found:    true,
					Verified: false, // not verified
					Tier:     "TIER3_MASTER_ARCHIVE",
				},
			},
		})
	}))
	defer server.Close()

	c := client.New(client.Config{BaseURL: server.URL, APIKey: "k", AgentID: "a"})
	eng := New(q, c)

	if err := q.RecordLocalMedia("local_uri_ineligible", "node-1", "b3", "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia: %v", err)
	}

	verdict, err := eng.SafeSpaceReclaim(context.Background(), "local_uri_ineligible")
	if err == nil {
		t.Fatalf("expected error for ineligible reclaim, got nil")
	}
	if verdict.Eligible {
		t.Fatalf("expected Eligible=false, got true")
	}

	// Confirm the local flag is NOT set.
	isOffloaded, _ := q.IsMediaOffloaded("local_uri_ineligible")
	if isOffloaded {
		t.Fatalf("expected NOT offloaded (server said not verified), but flag is set")
	}
}
