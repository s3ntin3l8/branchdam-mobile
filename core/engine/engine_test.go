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
		case "/api/v1/staging/upload":
			filename := r.Header.Get("X-Filename")
			uploadedFiles = append(uploadedFiles, filename)
			_, _ = io.ReadAll(r.Body)
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(client.UploadResponse{
				OK:       true,
				NodeUUID: "018f-test-node-1",
				FilePath: "/storage/staging/" + filename,
				Status:   "STAGED",
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
	if err := eng.SafeSpaceReclaim("local_uri_1"); err != nil {
		t.Fatalf("SafeSpaceReclaim failed: %v", err)
	}

	isOffloaded, err := q.IsMediaOffloaded("local_uri_1")
	if err != nil || !isOffloaded {
		t.Fatalf("expected media to be offloaded, got %v", isOffloaded)
	}
}
