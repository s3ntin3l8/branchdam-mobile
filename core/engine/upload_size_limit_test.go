package engine

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

// TestSyncUploads_RejectsOversizedFileOnRehash pins that the re-hash the
// upload path does when the file changed since it was enqueued is bounded
// too.
//
// The re-hash is the one hash site the size bound did not reach: it fires
// exactly when the file's size differs from the one recorded at enqueue,
// so a path replaced with a file of ANY size would be read to EOF while the
// batch holds the engine's read lock. The bound is the same 64 GiB the
// enqueue path and the FFI surface's ComputeHashes use.
//
// The limit is lowered for the test (the real one cannot be reached without
// writing 64 GiB), so this checks the wiring of the bound, not the value of
// the constant.
func TestSyncUploads_RejectsOversizedFileOnRehash(t *testing.T) {
	q, err := queue.Open(filepath.Join(t.TempDir(), "engine.db"))
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()

	// The server accepts an upload, so a file that is hashed despite being
	// over the limit would be uploaded and the row completed -- the test
	// then fails on the row instead of passing silently.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/agent/upload" {
			_, _ = io.ReadAll(r.Body)
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(client.UploadResponse{
				OK:         true,
				NodeUUID:   "uploaded-node-uuid",
				Status:     "UPLOADED",
				Blake3Hash: r.Header.Get("X-Blake3-Hash"),
			})
			return
		}
		http.NotFound(w, r)
	}))
	defer server.Close()

	dir := t.TempDir()
	path := filepath.Join(dir, "clip.mov")
	if err := os.WriteFile(path, []byte("more than four bytes"), 0644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	// The row records a different size, so the sync sees the file as
	// changed since it was enqueued and re-hashes it.
	id, err := q.EnqueueUpload(&queue.UploadItem{
		LocalPath:      path,
		TargetFilename: "clip.mov",
		Blake3Hash:     "b3stale",
		SizeBytes:      1,
	})
	if err != nil {
		t.Fatalf("EnqueueUpload: %v", err)
	}

	e := New(q, client.New(client.Config{BaseURL: server.URL, APIKey: "k", AgentID: "a"}))
	e.hashSizeLimit = 4
	if _, err := e.SyncUploads(context.Background(), 10); err != nil {
		t.Fatalf("SyncUploads: %v", err)
	}

	item, err := q.GetUploadItem(id)
	if err != nil {
		t.Fatalf("GetUploadItem: %v", err)
	}
	if item.Status == queue.UploadCompleted {
		t.Fatalf("item was completed: the oversized file was hashed and uploaded: %+v", item)
	}
	if !strings.Contains(item.ErrorMsg, "too large to hash") {
		t.Fatalf("ErrorMsg = %q, want the size-limit refusal", item.ErrorMsg)
	}
}
