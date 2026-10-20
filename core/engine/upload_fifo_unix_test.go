//go:build linux || darwin

package engine

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

// TestSyncUploads_RejectsFIFOWithoutBlocking pins that an upload row whose
// local path is a FIFO is refused BEFORE it is opened.
//
// The path crosses the FFI boundary from the native shell, so it is not
// necessarily a file. Opening a FIFO read-only blocks until some process
// opens the write end -- and the upload runs inside the engine's read
// lock (BindingSyncBatchWithRetry holds it for the whole batch), so a
// blocked open would hold that lock for good: the close queues behind it
// and, with a Go RWMutex, so does every later query. One FIFO path would
// wedge the sync, the close and every subsequent status query, and only an
// app restart would recover.
//
// The test would HANG (rather than fail) without the regular-file check,
// so it runs the sync in a goroutine and fails on a timeout.
func TestSyncUploads_RejectsFIFOWithoutBlocking(t *testing.T) {
	q, err := queue.Open(filepath.Join(t.TempDir(), "engine.db"))
	if err != nil {
		t.Fatalf("open queue: %v", err)
	}
	defer q.Close()

	// The server accepts an upload, so a path that is opened despite
	// being a FIFO is uploaded and the row is completed -- the test then
	// fails on the row instead of hanging.
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
		// check-content (and anything else) misses, so the pre-screen
		// does not short-circuit the upload.
		http.NotFound(w, r)
	}))
	defer server.Close()

	fifo := filepath.Join(t.TempDir(), "pipe")
	if err := syscall.Mkfifo(fifo, 0o600); err != nil {
		t.Fatalf("Mkfifo: %v", err)
	}
	id, err := q.EnqueueUpload(&queue.UploadItem{
		LocalPath:      fifo,
		TargetFilename: "pipe.mov",
		Blake3Hash:     "b3fifo",
	})
	if err != nil {
		t.Fatalf("EnqueueUpload: %v", err)
	}

	e := New(q, client.New(client.Config{BaseURL: server.URL, APIKey: "k", AgentID: "a"}))
	done := make(chan error, 1)
	go func() {
		_, err := e.SyncUploads(context.Background(), 10)
		done <- err
	}()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("SyncUploads: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("SyncUploads did not return: the open blocked on the FIFO")
	}

	// The row must be refused as a non-regular file. That refusal is made
	// before the open is attempted, so the recorded error is the
	// rejection and not an open failure.
	item, err := q.GetUploadItem(id)
	if err != nil {
		t.Fatalf("GetUploadItem: %v", err)
	}
	if item.Status == queue.UploadCompleted {
		t.Fatalf("item was completed: the FIFO was uploaded: %+v", item)
	}
	if !strings.Contains(item.ErrorMsg, "not a regular file") {
		t.Fatalf("ErrorMsg = %q, want the non-regular-file refusal", item.ErrorMsg)
	}
}
