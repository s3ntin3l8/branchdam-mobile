package engine

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

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
	// serverFullHash is what node-status reports as the stored BLAKE3;
	// set once the item is hashed, as the real server echoes the upload's.
	var serverFullHash string

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
						NodeUUID:       "018f-test-node-1",
						Found:          true,
						Tier:           "TIER3_MASTER_ARCHIVE",
						LifecycleState: "ACTIVE",
						Verified:       true,
						FullHash:       serverFullHash,
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
	item, err := eng.EnqueueLocalCapture(testFilePath, "PXL_20260829_001.dng", 1724000000, "local_uri_1", "", "")
	if err != nil {
		t.Fatalf("EnqueueLocalCapture failed: %v", err)
	}
	if item.Blake3Hash == "" || item.FastHash == "" {
		t.Fatalf("missing hashes in item: %+v", item)
	}
	serverFullHash = item.Blake3Hash

	// 2. Sync uploads
	uploadedCount, err := eng.SyncUploads(context.Background(), 10)
	if err != nil {
		t.Fatalf("SyncUploads failed: %v", err)
	}
	if uploadedCount != 1 || len(uploadedFiles) != 1 {
		t.Fatalf("expected 1 upload, got %d (server saw %d)", uploadedCount, len(uploadedFiles))
	}

	// 3. Enqueue and sync an event for the uploaded item (queued by local
	// ID, sent by the node UUID the upload returned).
	_, err = q.EnqueueEvent(EventNodeDeleted, `{"localId":"local_uri_1"}`)
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
	_ = q.RecordLocalMediaWithBaseline("local_uri_1", "018f-test-node-1", item.Blake3Hash, "ACTIVE", 1234, 1700000000)
	candidates, err := eng.CheckSafeSpaceCandidates(context.Background(), []string{"local_uri_1"})
	if err != nil {
		t.Fatalf("CheckSafeSpaceCandidates failed: %v", err)
	}
	if len(candidates) != 1 || !candidates[0].IsEligible || !candidates[0].IsVerified {
		t.Fatalf("expected eligible verified candidate, got: %+v", candidates)
	}

	// 5. Reclaim safe space
	if _, err := eng.SafeSpaceReclaim(context.Background(), "local_uri_1", LocalFileState{SizeBytes: 1234, ModifiedUnix: 1700000000}); err != nil {
		t.Fatalf("SafeSpaceReclaim failed: %v", err)
	}

	isOffloaded, err := q.IsMediaOffloaded("local_uri_1")
	if err != nil || !isOffloaded {
		t.Fatalf("expected media to be offloaded, got %v", isOffloaded)
	}
}

func TestCheckSafeSpaceCandidates_MultipleLocalIDsSharingNodeUUID(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "shared_node_test.db")
	q, err := queue.Open(dbPath)
	if err != nil {
		t.Fatalf("failed to open queue: %v", err)
	}
	defer q.Close()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/agent/node-status" {
			_ = json.NewEncoder(w).Encode(client.NodeStatusResponse{
				Statuses: []client.NodeStatusItem{
					{
						NodeUUID:       "shared-node-uuid",
						Found:          true,
						Verified:       true,
						Tier:           "TIER3_MASTER_ARCHIVE",
						LifecycleState: "ACTIVE",
						FullHash:       "hash-a",
					},
				},
			})
			return
		}
		http.NotFound(w, r)
	}))
	defer srv.Close()

	c := client.New(client.Config{BaseURL: srv.URL, AgentID: "test-agent"})
	eng := New(q, c)

	_ = q.RecordLocalMedia("local_uri_a", "shared-node-uuid", "hash-a", "ACTIVE")
	_ = q.RecordLocalMedia("local_uri_b", "shared-node-uuid", "hash-a", "ACTIVE")

	candidates, err := eng.CheckSafeSpaceCandidates(context.Background(), []string{"local_uri_a", "local_uri_b"})
	if err != nil {
		t.Fatalf("CheckSafeSpaceCandidates failed: %v", err)
	}
	if len(candidates) != 2 {
		t.Fatalf("expected 2 candidates, got %d", len(candidates))
	}
	for _, cand := range candidates {
		if !cand.IsEligible || !cand.IsVerified {
			t.Fatalf("expected candidate %s to be eligible and verified, got: %+v", cand.LocalID, cand)
		}
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
	item1, err := eng.EnqueueLocalCapture(testFilePath, "PXL_DUPLICATE.dng", 1724000000, "local_uri_dup_1", "", "")
	if err != nil {
		t.Fatalf("first EnqueueLocalCapture failed: %v", err)
	}

	// Verify count is 1
	count, err := q.CountPendingUploads()
	if err != nil || count != 1 {
		t.Fatalf("expected count 1, got %d", count)
	}

	// Second enqueue with same file content (same BLAKE3 hash)
	item2, err := eng.EnqueueLocalCapture(testFilePath, "PXL_DUPLICATE.dng", 1724000000, "local_uri_dup_2", "", "")
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

	item, err := eng.EnqueueLocalCapture(testFilePath, "PXL_SERVER_DUP.dng", 1724000000, "local_uri_srv", "", "")
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
	_, err = eng.EnqueueLocalCapture(missingPath, "does_not_exist.dng", 1724000000, "local_uri_missing", "", "")
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
		if r.URL.Path == "/api/v1/agent/upload" {
			uploads++
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(client.UploadResponse{
				OK:       true,
				NodeUUID: "resume-node-1",
				Status:   "UPLOADED",
			})
		} else {
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	c := client.New(client.Config{BaseURL: server.URL, APIKey: "k", AgentID: "a"})
	eng := New(q, c)

	testFilePath := filepath.Join(tempDir, "PXL_RESUME.dng")
	if err := os.WriteFile(testFilePath, []byte("resume"), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	if _, err := eng.EnqueueLocalCapture(testFilePath, "PXL_RESUME.dng", 1724000000, "local_uri_resume", "", ""); err != nil {
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
	item, err := eng.EnqueueLocalCapture(testFilePath, "PXL_DEDUP_EMPTY.dng", 1724000000, "local_uri_dup_empty", "", "")
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

// TestSyncUploads_Conflict409_RecordsNodeUUIDOnLocalMedia: when the upload
// itself is answered with the server's 409 dedup (the check-content
// pre-screen missed), the local media row must learn the existing node's
// UUID just as it does on the pre-screen and 201 paths. Without it the
// item's lineage events and Safe Space reclaim can never resolve a node.
func TestSyncUploads_Conflict409_RecordsNodeUUIDOnLocalMedia(t *testing.T) {
	tempDir := t.TempDir()
	q, err := queue.Open(filepath.Join(tempDir, "engine_409_node_test.db"))
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/agent/upload" {
			_, _ = io.ReadAll(r.Body)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusConflict)
			_ = json.NewEncoder(w).Encode(client.UploadResponse{
				NodeUUID: "existing-node-409",
				FilePath: "/storage/archive/PXL_409.dng",
			})
			return
		}
		// check-content (and anything else) misses, so the item is
		// streamed and the dedup answer comes from the upload itself.
		http.NotFound(w, r)
	}))
	defer server.Close()

	c := client.New(client.Config{BaseURL: server.URL, APIKey: "k", AgentID: "a"})
	eng := New(q, c)

	testFilePath := filepath.Join(tempDir, "PXL_409.dng")
	if err := os.WriteFile(testFilePath, []byte("409 dedup bytes"), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	const localID = "content://media/external/images/media/409"
	item, err := eng.EnqueueLocalCapture(testFilePath, "PXL_409.dng", 1724000000, localID, "", "")
	if err != nil {
		t.Fatalf("EnqueueLocalCapture: %v", err)
	}

	before, err := q.GetMediaByLocalID(localID)
	if err != nil {
		t.Fatalf("GetMediaByLocalID before sync: %v", err)
	}
	if before.NodeUUID != "" {
		t.Fatalf("precondition: local media row already has node %q", before.NodeUUID)
	}

	completed, err := eng.SyncUploads(context.Background(), 10)
	if err != nil {
		t.Fatalf("SyncUploads: %v", err)
	}
	if completed != 1 {
		t.Fatalf("expected the 409 dedup to complete the item, got %d completed", completed)
	}

	up, err := q.GetUploadItemByBlake3Hash(item.Blake3Hash)
	if err != nil || up == nil {
		t.Fatalf("GetUploadItemByBlake3Hash: %v", err)
	}
	if up.Status != queue.UploadCompleted || up.NodeUUID != "existing-node-409" {
		t.Fatalf("upload row after 409 dedup: %+v", up)
	}

	after, err := q.GetMediaByLocalID(localID)
	if err != nil {
		t.Fatalf("GetMediaByLocalID after sync: %v", err)
	}
	if after.NodeUUID != "existing-node-409" {
		t.Fatalf("local media row node = %q, want existing-node-409 (409 dedup must record it)", after.NodeUUID)
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

	verdict, err := eng.SafeSpaceReclaim(context.Background(), "local_uri_ineligible", LocalFileState{})
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

func TestEnqueueLocalCapture_SourcePathHash(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "engine_srcpath_test.db")
	q, err := queue.Open(dbPath)
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()

	c := client.New(client.Config{AgentID: "fallback-camera-model"})
	eng := New(q, c)

	f1 := filepath.Join(tempDir, "f1.dng")
	if err := os.WriteFile(f1, []byte("f1-bytes"), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	// 1. Explicit source path hash (64 lowercase hex) and camera model
	validCustomHash := "a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00" // pragma: allowlist secret
	item1, err := eng.EnqueueLocalCapture(f1, "f1.dng", 1724000000, "local-1", "Sony-A7IV", validCustomHash)
	if err != nil {
		t.Fatalf("EnqueueLocalCapture: %v", err)
	}
	if item1.CameraModel != "Sony-A7IV" {
		t.Fatalf("CameraModel = %q, want Sony-A7IV", item1.CameraModel)
	}
	if item1.SourcePathHash != validCustomHash {
		t.Fatalf("SourcePathHash = %q, want %q", item1.SourcePathHash, validCustomHash)
	}

	// 2. Fallback source path hash derives from stable localID across launches
	f2 := filepath.Join(tempDir, "f2.dng")
	if err := os.WriteFile(f2, []byte("f2-bytes"), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	item2, err := eng.EnqueueLocalCapture(f2, "f2.dng", 1724000000, "local-2", "", "")
	if err != nil {
		t.Fatalf("EnqueueLocalCapture: %v", err)
	}
	if item2.CameraModel != "fallback-camera-model" {
		t.Fatalf("CameraModel = %q, want fallback-camera-model", item2.CameraModel)
	}
	expectedHash := sha256.Sum256([]byte("local-2"))
	expectedHashHex := hex.EncodeToString(expectedHash[:])
	if item2.SourcePathHash != expectedHashHex {
		t.Fatalf("SourcePathHash = %q, want %q", item2.SourcePathHash, expectedHashHex)
	}

	// 3. When localID is empty, fallback derives from localPath
	f3 := filepath.Join(tempDir, "f3.dng")
	if err := os.WriteFile(f3, []byte("f3-bytes"), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	item3, err := eng.EnqueueLocalCapture(f3, "f3.dng", 1724000000, "", "", "")
	if err != nil {
		t.Fatalf("EnqueueLocalCapture: %v", err)
	}
	expectedHash3 := sha256.Sum256([]byte(f3))
	expectedHash3Hex := hex.EncodeToString(expectedHash3[:])
	if item3.SourcePathHash != expectedHash3Hex {
		t.Fatalf("SourcePathHash = %q, want %q", item3.SourcePathHash, expectedHash3Hex)
	}

	// 4. Invalid sourcePathHash rejected at enqueue with CodeInvalidInput
	_, err = eng.EnqueueLocalCapture(f1, "f1.dng", 1724000000, "local-err", "", "not-64-hex")
	if err == nil {
		t.Fatalf("expected error for invalid sourcePathHash, got nil")
	}
	ce, ok := err.(*client.ClientError)
	if !ok || ce.Code != client.CodeInvalidInput {
		t.Fatalf("expected CodeInvalidInput, got %v", err)
	}
}

func TestSyncUploads_BackgroundPreScreen(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "engine_prescreen_test.db")
	q, err := queue.Open(dbPath)
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/agent/check-content" {
			_ = json.NewEncoder(w).Encode(client.ContentCheckResult{
				Found:    true,
				NodeUUID: "prescreened-node-uuid-123",
			})
			return
		}
		http.NotFound(w, r)
	}))
	defer server.Close()

	c := client.New(client.Config{BaseURL: server.URL, APIKey: "k", AgentID: "a"})
	eng := New(q, c)

	testFilePath := filepath.Join(tempDir, "PXL_PRESCREEN.dng")
	if err := os.WriteFile(testFilePath, []byte("prescreen bytes"), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	item, err := eng.EnqueueLocalCapture(testFilePath, "PXL_PRESCREEN.dng", 1724000000, "local_uri_prescreen", "", "")
	if err != nil {
		t.Fatalf("EnqueueLocalCapture: %v", err)
	}

	// Local enqueue is fast and PENDING
	if item.Status != queue.UploadPending {
		t.Fatalf("expected Status PENDING on enqueue, got %s", item.Status)
	}

	// Background sync runs pre-screen and marks completed without streaming bytes
	completedCount, err := eng.SyncUploads(context.Background(), 10)
	if err != nil || completedCount != 1 {
		t.Fatalf("SyncUploads failed: %v, count=%d", err, completedCount)
	}

	// Verify upload item in queue is completed
	state, err := q.GetUploadItemByBlake3Hash(item.Blake3Hash)
	if err != nil || state == nil {
		t.Fatalf("GetUploadItemByBlake3Hash failed: %v", err)
	}
	if state.Status != queue.UploadCompleted || state.NodeUUID != "prescreened-node-uuid-123" {
		t.Fatalf("unexpected upload state after background pre-screen: %+v", state)
	}
}

func TestEngine_GetMediaStatus_And_CountPendingUploads(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "engine_status_test.db")
	q, err := queue.Open(dbPath)
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()

	eng := New(q, nil)

	st, err := eng.GetMediaStatus("unknown-local-id")
	if err != nil || st != "NOT_ENQUEUED" {
		t.Fatalf("expected NOT_ENQUEUED, got %s (err: %v)", st, err)
	}

	count, err := eng.CountPendingUploads()
	if err != nil || count != 0 {
		t.Fatalf("expected 0 pending uploads, got %d (err: %v)", count, err)
	}

	all, err := eng.GetAllMediaStatuses()
	if err != nil {
		t.Fatalf("GetAllMediaStatuses failed: %v", err)
	}
	if len(all) != 0 {
		t.Fatalf("expected empty map, got %v", all)
	}
}

func TestEngine_ActiveUploadProgress_FirstSampleAndSpeed(t *testing.T) {
	eng := New(nil, nil)
	eng.setActiveUpload(1, "test.dng", 1000000, 1, 1)

	p, err := eng.GetActiveUploadProgress()
	if err != nil || p == nil {
		t.Fatalf("GetActiveUploadProgress returned nil")
	}
	if p.BytesSent != 0 || p.SpeedBytesPerSec != 0 {
		t.Fatalf("initial progress: bytesSent=%d speed=%f", p.BytesSent, p.SpeedBytesPerSec)
	}

	// First callback: seeds lastProgressSent, speed stays 0
	eng.updateActiveUploadProgress(100000, 1000000)
	p, _ = eng.GetActiveUploadProgress()
	if p.BytesSent != 100000 || p.SpeedBytesPerSec != 0 {
		t.Fatalf("first sample progress: bytesSent=%d speed=%f (want speed 0)", p.BytesSent, p.SpeedBytesPerSec)
	}

	// Simulate time passing by altering internal lastProgressTime
	eng.activeMu.Lock()
	eng.lastProgressTime = eng.lastProgressTime.Add(-1 * time.Second)
	eng.activeMu.Unlock()

	// Second sample after 1s: 200,000 additional bytes in 1s = 200,000 B/s
	eng.updateActiveUploadProgress(300000, 1000000)
	p, _ = eng.GetActiveUploadProgress()
	if p.BytesSent != 300000 {
		t.Fatalf("second sample bytesSent=%d", p.BytesSent)
	}
	if p.SpeedBytesPerSec < 190000 || p.SpeedBytesPerSec > 210000 {
		t.Fatalf("second sample speed = %f, want ~200000", p.SpeedBytesPerSec)
	}
}

func TestEngine_ResetUploadByBlake3Hash_UpdatesPath(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "engine_reset_path_test.db")
	q, err := queue.Open(dbPath)
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()

	eng := New(q, nil)

	file1 := filepath.Join(tempDir, "file1.dng")
	testData := []byte("recaptured asset content")
	if err := os.WriteFile(file1, testData, 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	item1, err := eng.EnqueueLocalCapture(file1, "file1.dng", 1000, "local-1", "", "")
	if err != nil {
		t.Fatalf("EnqueueLocalCapture 1: %v", err)
	}

	// Mark item as FAILED (maxRetries = 1 so retry_count = 1 >= maxRetries sets FAILED)
	if err := q.MarkUploadFailed(item1.ID, "simulated failure", 1); err != nil {
		t.Fatalf("MarkUploadFailed: %v", err)
	}

	// Re-enqueue at a new location file2 (same content/hash)
	file2 := filepath.Join(tempDir, "file2.dng")
	if err := os.WriteFile(file2, testData, 0644); err != nil {
		t.Fatalf("WriteFile 2: %v", err)
	}

	item2, err := eng.EnqueueLocalCapture(file2, "file2.dng", 1000, "local-2", "", "")
	if err != nil {
		t.Fatalf("EnqueueLocalCapture 2: %v", err)
	}

	if item1.ID != item2.ID {
		t.Fatalf("item IDs should match: %d != %d", item1.ID, item2.ID)
	}

	// Verify local_path in queue row updated to file2
	items, err := q.ClaimPendingUploads(1, 0, 5)
	if err != nil || len(items) != 1 {
		t.Fatalf("ClaimPendingUploads: %v, len=%d", err, len(items))
	}
	if items[0].LocalPath != file2 {
		t.Fatalf("LocalPath = %q, want %q", items[0].LocalPath, file2)
	}
}

// newNodeStatusServer mimics branchDAM's POST /api/v1/agent/node-status:
// one entry per requested UUID (Found=false when the UUID is unknown),
// and a 400 for an empty or >400-UUID request. batchSizes records the
// size of every request the engine sent.
func newNodeStatusServer(t *testing.T, known map[string]client.NodeStatusItem) (srv *httptest.Server, batchSizes *[]int) {
	t.Helper()
	sizes := []int{}
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/node-status" {
			http.NotFound(w, r)
			return
		}
		var req client.NodeStatusRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		sizes = append(sizes, len(req.NodeUUIDs))
		if len(req.NodeUUIDs) == 0 || len(req.NodeUUIDs) > 400 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		out := make([]client.NodeStatusItem, 0, len(req.NodeUUIDs))
		for _, id := range req.NodeUUIDs {
			st, ok := known[id]
			if !ok {
				st = client.NodeStatusItem{NodeUUID: id}
			}
			out = append(out, st)
		}
		_ = json.NewEncoder(w).Encode(client.NodeStatusResponse{Statuses: out})
	}))
	t.Cleanup(srv.Close)
	return srv, &sizes
}

// TestReclaimGate_RequiresLiveTier3Master: a local original may only be
// reclaimed when the server's copy is a found, hash-verified Tier-3 master
// in a live lifecycle state whose stored fullHash equals (case-insensitively)
// the BLAKE3 recorded locally (#217). Both reclaim-eligibility paths
// (CheckSafeSpaceCandidates and SafeSpaceReclaim) must agree, and a
// rejected reclaim must never set the offloaded flag.
func TestReclaimGate_RequiresLiveTier3Master(t *testing.T) {
	// localB3 is the BLAKE3 recorded in queue.db for the candidate unless a
	// case sets noLocalHash.
	const localB3 = "a3ce4343025b0f98877e30d01847e2a882607401dd97d5f173fbec1d2f9fffa4" // pragma: allowlist secret
	const otherB3 = "abababababababababababababababababababababababababababababababab"
	// The local item is unchanged since enqueue in every case here; the
	// baseline gate has its own test (TestSafeSpaceReclaim_LocalChanged).
	const gateSize, gateMtime = int64(4096), int64(1700000000)
	cases := []struct {
		name        string
		status      client.NodeStatusItem
		eligible    bool
		reasonPart  string
		noLocalHash bool
	}{
		{"active tier3 verified", client.NodeStatusItem{Found: true, Verified: true, Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "ACTIVE", FullHash: localB3}, true, "", false},
		{"hidden tier3 verified", client.NodeStatusItem{Found: true, Verified: true, Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "HIDDEN", FullHash: localB3}, true, "", false},
		{"fullHash matches case-insensitively", client.NodeStatusItem{Found: true, Verified: true, Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "ACTIVE", FullHash: strings.ToUpper(localB3)}, true, "", false},
		{"fullHash omitted by server", client.NodeStatusItem{Found: true, Verified: true, Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "ACTIVE"}, false, ReasonNoServerHash, false},
		{"fullHash mismatch", client.NodeStatusItem{Found: true, Verified: true, Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "ACTIVE", FullHash: otherB3}, false, ReasonHashMismatch, false},
		{"no local hash recorded", client.NodeStatusItem{Found: true, Verified: true, Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "ACTIVE", FullHash: localB3}, false, ReasonNoLocalHash, true},
		// An earlier failing gate is still the reason shown even when the
		// hash would also fail.
		{"not verified and hash mismatch", client.NodeStatusItem{Found: true, Verified: false, Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "ACTIVE", FullHash: otherB3}, false, "not verified on server", false},
		{"trashed master", client.NodeStatusItem{Found: true, Verified: true, Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "TRASHED"}, false, "lifecycle ineligible: TRASHED", false},
		{"missing master", client.NodeStatusItem{Found: true, Verified: true, Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "MISSING"}, false, "lifecycle ineligible: MISSING", false},
		{"archived (superseded) master", client.NodeStatusItem{Found: true, Verified: true, Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "ARCHIVED"}, false, "lifecycle ineligible: ARCHIVED", false},
		{"lifecycle absent", client.NodeStatusItem{Found: true, Verified: true, Tier: "TIER3_MASTER_ARCHIVE"}, false, "lifecycle ineligible: <none>", false},
		{"legacy non-server tier", client.NodeStatusItem{Found: true, Verified: true, Tier: "TIER2_DERIVATIVE_CACHE", LifecycleState: "ACTIVE"}, false, "tier ineligible: TIER2_DERIVATIVE_CACHE", false},
		{"exports tier", client.NodeStatusItem{Found: true, Verified: true, Tier: "TIER2_EXPORTS", LifecycleState: "ACTIVE"}, false, "tier ineligible: TIER2_EXPORTS", false},
		{"scratch tier", client.NodeStatusItem{Found: true, Verified: true, Tier: "TIER1_LOCAL_SCRATCH", LifecycleState: "ACTIVE"}, false, "tier ineligible: TIER1_LOCAL_SCRATCH", false},
		{"tier absent", client.NodeStatusItem{Found: true, Verified: true, LifecycleState: "ACTIVE"}, false, "tier ineligible: <none>", false},
		{"not verified", client.NodeStatusItem{Found: true, Verified: false, Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "ACTIVE"}, false, "not verified on server", false},
		{"not found", client.NodeStatusItem{Found: false}, false, "node not found on server", false},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			const nodeUUID = "node-under-test"
			const localID = "local-under-test"
			st := tc.status
			st.NodeUUID = nodeUUID
			srv, _ := newNodeStatusServer(t, map[string]client.NodeStatusItem{nodeUUID: st})

			q, err := queue.Open(filepath.Join(t.TempDir(), "gate.db"))
			if err != nil {
				t.Fatalf("open queue: %v", err)
			}
			defer q.Close()
			eng := New(q, client.New(client.Config{BaseURL: srv.URL, APIKey: "k", AgentID: "a"}))
			localHash := localB3
			if tc.noLocalHash {
				localHash = ""
			}
			if err := q.RecordLocalMediaWithBaseline(localID, nodeUUID, localHash, "ACTIVE", gateSize, gateMtime); err != nil {
				t.Fatalf("RecordLocalMedia: %v", err)
			}

			// Scan path.
			cands, err := eng.CheckSafeSpaceCandidates(context.Background(), []string{localID})
			if err != nil {
				t.Fatalf("CheckSafeSpaceCandidates: %v", err)
			}
			if len(cands) != 1 {
				t.Fatalf("got %d candidates, want 1", len(cands))
			}
			if cands[0].IsEligible != tc.eligible {
				t.Fatalf("scan IsEligible = %v, want %v (candidate %+v)", cands[0].IsEligible, tc.eligible, cands[0])
			}
			if tc.eligible && cands[0].Reason != "" {
				t.Fatalf("eligible candidate carries Reason %q", cands[0].Reason)
			}
			if !tc.eligible && !strings.Contains(cands[0].Reason, tc.reasonPart) {
				t.Fatalf("scan Reason = %q, want substring %q", cands[0].Reason, tc.reasonPart)
			}

			// Reclaim path.
			verdict, err := eng.SafeSpaceReclaim(context.Background(), localID, LocalFileState{SizeBytes: gateSize, ModifiedUnix: gateMtime})
			if verdict.Eligible != tc.eligible {
				t.Fatalf("reclaim Eligible = %v, want %v (verdict %+v, err %v)", verdict.Eligible, tc.eligible, verdict, err)
			}
			if tc.eligible {
				if err != nil {
					t.Fatalf("reclaim err = %v, want nil", err)
				}
			} else {
				if err == nil {
					t.Fatalf("reclaim err = nil, want ineligible error")
				}
				if !strings.Contains(verdict.Reason, tc.reasonPart) {
					t.Fatalf("reclaim Reason = %q, want substring %q", verdict.Reason, tc.reasonPart)
				}
			}
			offloaded, err := q.IsMediaOffloaded(localID)
			if err != nil {
				t.Fatalf("IsMediaOffloaded: %v", err)
			}
			if offloaded != tc.eligible {
				t.Fatalf("offloaded flag = %v, want %v", offloaded, tc.eligible)
			}
		})
	}
}

// TestSafeSpaceReclaim_IgnoresStatusForOtherNode: the reclaim decision must
// be based on the entry whose nodeUuid matches the asset's node, never on
// whatever entry happens to come first.
func TestSafeSpaceReclaim_IgnoresStatusForOtherNode(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(client.NodeStatusResponse{
			Statuses: []client.NodeStatusItem{{
				NodeUUID: "some-other-node", Found: true, Verified: true,
				Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "ACTIVE",
			}},
		})
	}))
	defer srv.Close()

	q, err := queue.Open(filepath.Join(t.TempDir(), "mismatch.db"))
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()
	eng := New(q, client.New(client.Config{BaseURL: srv.URL, APIKey: "k", AgentID: "a"}))
	if err := q.RecordLocalMedia("local-x", "node-x", "b3", "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia: %v", err)
	}

	verdict, err := eng.SafeSpaceReclaim(context.Background(), "local-x", LocalFileState{})
	if err == nil || verdict.Eligible {
		t.Fatalf("expected ineligible verdict, got %+v, err %v", verdict, err)
	}
	if offloaded, _ := q.IsMediaOffloaded("local-x"); offloaded {
		t.Fatalf("offloaded flag set from another node's status")
	}
}

// TestCheckSafeSpaceCandidates_BatchesNodeStatusAt400: the server rejects
// node-status requests carrying more than 400 UUIDs with a 400, which used
// to fail the whole Safe-Space scan. 401 distinct nodes must go out as two
// requests (400 + 1) and every candidate must come back evaluated.
func TestCheckSafeSpaceCandidates_BatchesNodeStatusAt400(t *testing.T) {
	const n = client.MaxNodeStatusBatch + 1
	known := make(map[string]client.NodeStatusItem, n)
	localIDs := make([]string, 0, n)

	q, err := queue.Open(filepath.Join(t.TempDir(), "batch.db"))
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()
	for i := 0; i < n; i++ {
		nodeUUID := fmt.Sprintf("node-%04d", i)
		localID := fmt.Sprintf("local-%04d", i)
		known[nodeUUID] = client.NodeStatusItem{
			NodeUUID: nodeUUID, Found: true, Verified: true,
			Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "ACTIVE", FullHash: "b3",
		}
		if err := q.RecordLocalMedia(localID, nodeUUID, "b3", "ACTIVE"); err != nil {
			t.Fatalf("RecordLocalMedia: %v", err)
		}
		localIDs = append(localIDs, localID)
	}
	srv, sizes := newNodeStatusServer(t, known)
	eng := New(q, client.New(client.Config{BaseURL: srv.URL, APIKey: "k", AgentID: "a"}))

	cands, err := eng.CheckSafeSpaceCandidates(context.Background(), localIDs)
	if err != nil {
		t.Fatalf("CheckSafeSpaceCandidates: %v", err)
	}
	if got := *sizes; len(got) != 2 || got[0] != 400 || got[1] != 1 {
		t.Fatalf("node-status request sizes = %v, want [400 1]", got)
	}
	if len(cands) != n {
		t.Fatalf("got %d candidates, want %d", len(cands), n)
	}
	for i, c := range cands {
		if c.LocalID != localIDs[i] {
			t.Fatalf("candidate %d LocalID = %q, want %q (order must follow input)", i, c.LocalID, localIDs[i])
		}
		if !c.IsEligible {
			t.Fatalf("candidate %s not eligible: %+v", c.LocalID, c)
		}
	}
}

// TestCheckSafeSpaceCandidates_DedupesSharedNodeUUIDs: several local IDs
// mapping to one node send that UUID once, so the batch budget is spent on
// distinct nodes only.
func TestCheckSafeSpaceCandidates_DedupesSharedNodeUUIDs(t *testing.T) {
	srv, sizes := newNodeStatusServer(t, map[string]client.NodeStatusItem{
		"shared": {NodeUUID: "shared", Found: true, Verified: true, Tier: "TIER3_MASTER_ARCHIVE", LifecycleState: "ACTIVE", FullHash: "b3"},
	})
	q, err := queue.Open(filepath.Join(t.TempDir(), "dedupe.db"))
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()
	eng := New(q, client.New(client.Config{BaseURL: srv.URL, APIKey: "k", AgentID: "a"}))
	for _, id := range []string{"a", "b", "c"} {
		if err := q.RecordLocalMedia(id, "shared", "b3", "ACTIVE"); err != nil {
			t.Fatalf("RecordLocalMedia: %v", err)
		}
	}
	cands, err := eng.CheckSafeSpaceCandidates(context.Background(), []string{"a", "b", "c"})
	if err != nil {
		t.Fatalf("CheckSafeSpaceCandidates: %v", err)
	}
	if got := *sizes; len(got) != 1 || got[0] != 1 {
		t.Fatalf("node-status request sizes = %v, want [1]", got)
	}
	for _, c := range cands {
		if !c.IsEligible {
			t.Fatalf("candidate %s not eligible: %+v", c.LocalID, c)
		}
	}
}

// TestSafeSpaceReclaim_LocalChanged (#229): the BLAKE3 in queue.db speaks
// only for the bytes the item had at enqueue. Reclaim deletes only when
// the state the shell reads just before deleting matches the baseline
// recorded with that hash; a localID whose bytes were replaced in place
// since upload (size or mtime moved) is kept even though the server still
// holds a matching hash for the old bytes. The scan path does not see the
// current state and is unaffected.
func TestSafeSpaceReclaim_LocalChanged(t *testing.T) {
	const b3 = "a3ce4343025b0f98877e30d01847e2a882607401dd97d5f173fbec1d2f9fffa4" // pragma: allowlist secret
	const size, mtime = int64(4096), int64(1700000000)
	cases := []struct {
		name             string
		baseSize, baseMt int64
		current          LocalFileState
		eligible         bool
		reasonPart       string
	}{
		{"unchanged", size, mtime, LocalFileState{SizeBytes: size, ModifiedUnix: mtime}, true, ""},
		{"size changed", size, mtime, LocalFileState{SizeBytes: size + 1, ModifiedUnix: mtime}, false, ReasonLocalChanged + ": size"},
		{"mtime changed", size, mtime, LocalFileState{SizeBytes: size, ModifiedUnix: mtime + 1}, false, ReasonLocalChanged + ": modification time"},
		{"current state unknown", size, mtime, LocalFileState{}, false, ReasonLocalChanged},
		{"no baseline recorded", 0, 0, LocalFileState{SizeBytes: size, ModifiedUnix: mtime}, false, ReasonNoLocalBaseline},
		// iOS records no size (PhotoKit has no public one): only the
		// recorded mtime is compared.
		{"mtime-only baseline unchanged", 0, mtime, LocalFileState{ModifiedUnix: mtime}, true, ""},
		{"mtime-only baseline changed", 0, mtime, LocalFileState{ModifiedUnix: mtime + 60}, false, ReasonLocalChanged + ": modification time"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			const nodeUUID = "node-229"
			const localID = "content://media/external/images/media/229"
			srv, _ := newNodeStatusServer(t, map[string]client.NodeStatusItem{nodeUUID: {
				NodeUUID: nodeUUID, Found: true, Verified: true,
				Tier: ReclaimTier, LifecycleState: "ACTIVE", FullHash: b3,
			}})
			q, err := queue.Open(filepath.Join(t.TempDir(), "changed.db"))
			if err != nil {
				t.Fatalf("open queue: %v", err)
			}
			defer q.Close()
			eng := New(q, client.New(client.Config{BaseURL: srv.URL, APIKey: "k", AgentID: "a"}))
			if err := q.RecordLocalMediaWithBaseline(localID, nodeUUID, b3, "ACTIVE", tc.baseSize, tc.baseMt); err != nil {
				t.Fatalf("RecordLocalMediaWithBaseline: %v", err)
			}

			verdict, err := eng.SafeSpaceReclaim(context.Background(), localID, tc.current)
			if verdict.Eligible != tc.eligible {
				t.Fatalf("Eligible = %v, want %v (verdict %+v, err %v)", verdict.Eligible, tc.eligible, verdict, err)
			}
			if tc.eligible && err != nil {
				t.Fatalf("err = %v, want nil", err)
			}
			if !tc.eligible {
				if err == nil {
					t.Fatalf("err = nil, want ineligible error")
				}
				if !strings.Contains(verdict.Reason, tc.reasonPart) {
					t.Fatalf("Reason = %q, want substring %q", verdict.Reason, tc.reasonPart)
				}
			}
			offloaded, err := q.IsMediaOffloaded(localID)
			if err != nil {
				t.Fatalf("IsMediaOffloaded: %v", err)
			}
			if offloaded != tc.eligible {
				t.Fatalf("offloaded flag = %v, want %v", offloaded, tc.eligible)
			}
		})
	}
}

// TestEnqueueLocalCaptureWithBaseline_RecordsBaseline: the baseline the
// shell passes at enqueue lands next to the BLAKE3, and a re-enqueue that
// dedups against the existing upload records the new baseline with the
// freshly computed hash.
func TestEnqueueLocalCaptureWithBaseline_RecordsBaseline(t *testing.T) {
	dir := t.TempDir()
	q, err := queue.Open(filepath.Join(dir, "baseline.db"))
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()
	eng := New(q, nil)
	path := filepath.Join(dir, "IMG_0229.jpg")
	if err := os.WriteFile(path, []byte("original bytes"), 0o600); err != nil {
		t.Fatal(err)
	}
	const localID = "content://media/external/images/media/229"
	item, err := eng.EnqueueLocalCaptureWithBaseline(path, "IMG_0229.jpg", 1, localID, "cam", "", LocalFileState{SizeBytes: 14, ModifiedUnix: 1700000000})
	if err != nil {
		t.Fatalf("enqueue: %v", err)
	}
	m, err := q.GetMediaByLocalID(localID)
	if err != nil {
		t.Fatal(err)
	}
	if m.Blake3Hash != item.Blake3Hash || m.SizeBytes != 14 || m.ModifiedUnix != 1700000000 {
		t.Fatalf("recorded = %+v, want hash %s and baseline (14, 1700000000)", m, item.Blake3Hash)
	}

	if _, err := eng.EnqueueLocalCaptureWithBaseline(path, "IMG_0229.jpg", 1, localID, "cam", "", LocalFileState{SizeBytes: 14, ModifiedUnix: 1700000500}); err != nil {
		t.Fatalf("re-enqueue: %v", err)
	}
	if m, _ := q.GetMediaByLocalID(localID); m.ModifiedUnix != 1700000500 {
		t.Fatalf("re-enqueue baseline mtime = %d, want 1700000500", m.ModifiedUnix)
	}
}
