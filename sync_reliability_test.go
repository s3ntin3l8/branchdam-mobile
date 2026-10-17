package branchdam

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

// newStatusEngine opens an Engine against a server that answers every
// agent call with status (check-content says "not found").
func newStatusEngine(t *testing.T, status int) *Engine {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/agent/check-content" {
			_ = json.NewEncoder(w).Encode(client.ContentCheckResult{Found: false})
			return
		}
		w.WriteHeader(status)
		_, _ = w.Write([]byte(`{"error":"rejected"}`))
	}))
	t.Cleanup(server.Close)
	e, err := NewEngine(EngineOptions{
		DBPath:            filepath.Join(t.TempDir(), "engine.db"),
		BaseURL:           server.URL,
		APIKey:            "k",
		AgentID:           "pixel",
		DevCleartextHosts: []string{"127.0.0.1"},
	})
	if err != nil {
		t.Fatalf("NewEngine: %v", err)
	}
	t.Cleanup(func() { _ = e.Close() })
	return e
}

func enqueueTestMedia(t *testing.T, e *Engine, name string) int64 {
	t.Helper()
	p := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(p, []byte("bytes of "+name), 0o644); err != nil {
		t.Fatal(err)
	}
	id, err := e.EnqueueMedia(EnqueueMediaOptions{LocalPath: p, Filename: name, LocalID: "local:" + name})
	if err != nil {
		t.Fatalf("EnqueueMedia: %v", err)
	}
	return id
}

func errorCode(t *testing.T, err error) string {
	t.Helper()
	var be *Error
	if !errors.As(err, &be) {
		t.Fatalf("err = %v (%T), want *Error", err, err)
	}
	return be.Code
}

// TestFFI_AuthRejectionMapsToAuthFailed: a 401/403 reaches the shells as
// AUTH_FAILED (re-pair), with the status still in the message the shells
// match on today; a 5xx stays NETWORK_ERROR.
func TestFFI_AuthRejectionMapsToAuthFailed(t *testing.T) {
	for _, c := range []struct {
		status int
		code   string
	}{
		{http.StatusUnauthorized, CodeAuthFailed},
		{http.StatusForbidden, CodeAuthFailed},
		{http.StatusInternalServerError, CodeNetworkError},
	} {
		t.Run(http.StatusText(c.status), func(t *testing.T) {
			e := newStatusEngine(t, c.status)

			_, err := e.FetchNamingTemplate()
			if got := errorCode(t, err); got != c.code {
				t.Errorf("FetchNamingTemplate code = %s, want %s", got, c.code)
			}
			if !strings.Contains(err.Error(), strconv.Itoa(c.status)) {
				t.Errorf("message %q lost the status", err.Error())
			}

			if err := e.queue.RecordLocalMedia("ph://x", "node-x", "b3-x", "ACTIVE"); err != nil {
				t.Fatal(err)
			}
			_, err = e.ReclaimSafeSpace("ph://x", 0, 0)
			want := c.code
			if c.code == CodeNetworkError {
				want = "INTERNAL" // reclaim's transient code, unchanged
			}
			if got := errorCode(t, err); got != want {
				t.Errorf("ReclaimSafeSpace code = %s, want %s", got, want)
			}
		})
	}
}

// TestSyncBatch_AuthFailureSurfacesAndKeepsRetries: SyncBatch used to
// swallow upload failures; a credential rejection now comes back as
// AUTH_FAILED and the item keeps its retry budget for after re-pairing.
func TestSyncBatch_AuthFailureSurfacesAndKeepsRetries(t *testing.T) {
	e := newStatusEngine(t, http.StatusUnauthorized)
	id := enqueueTestMedia(t, e, "a.jpg")

	_, err := e.SyncBatch(SyncOptions{TimeoutSecs: 10, IncludeUploads: true, IncludeEvents: true})
	if got := errorCode(t, err); got != CodeAuthFailed {
		t.Fatalf("SyncBatch code = %s, want AUTH_FAILED", got)
	}
	item, err := e.queue.GetUploadItem(id)
	if err != nil {
		t.Fatal(err)
	}
	if item.Status != queue.UploadPending || item.RetryCount != 0 {
		t.Fatalf("item = %s/%d, want PENDING/0", item.Status, item.RetryCount)
	}
}

// TestSyncBatch_HonorsMaxRetries: SyncOptions.MaxRetries reaches the
// engine (it used to be computed and dropped, with 5 hard-coded).
func TestSyncBatch_HonorsMaxRetries(t *testing.T) {
	e := newStatusEngine(t, http.StatusInternalServerError)
	id := enqueueTestMedia(t, e, "a.jpg")

	if _, err := e.SyncBatch(SyncOptions{TimeoutSecs: 10, IncludeUploads: true, MaxRetries: 1}); err != nil {
		t.Fatalf("SyncBatch: %v", err)
	}
	item, err := e.queue.GetUploadItem(id)
	if err != nil {
		t.Fatal(err)
	}
	if item.Status != queue.UploadFailed || item.RetryCount != 1 {
		t.Fatalf("item = %s/%d, want FAILED/1 with MaxRetries 1", item.Status, item.RetryCount)
	}
}

// TestBindingSyncBatchWithRetry_PassesPolicy: the binding spelling of the
// retry options reaches the engine too.
func TestBindingSyncBatchWithRetry_PassesPolicy(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/agent/check-content" {
			_ = json.NewEncoder(w).Encode(client.ContentCheckResult{Found: false})
			return
		}
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer server.Close()
	if err := BindingOpen(filepath.Join(t.TempDir(), "engine.db"), server.URL, "k", "pixel", "1", "127.0.0.1"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	defer BindingClose()
	e, _ := getBindingEngine()
	id := enqueueTestMedia(t, e, "b.jpg")

	if _, err := BindingSyncBatchWithRetry(10, 5, 30, 1); err != nil {
		t.Fatalf("BindingSyncBatchWithRetry: %v", err)
	}
	item, err := e.queue.GetUploadItem(id)
	if err != nil {
		t.Fatal(err)
	}
	if item.Status != queue.UploadFailed {
		t.Fatalf("item = %s/%d, want FAILED with maxRetries 1", item.Status, item.RetryCount)
	}
}

// TestBindingPendingImportRoundTrip covers the pending-import FFI surface
// end to end, including surviving a close and reopen.
func TestBindingPendingImportRoundTrip(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "engine.db")
	if err := BindingOpen(dbPath, "http://localhost", "", "test", "test", "localhost"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	defer BindingClose()

	staged, err := BindingStagePendingImport("content://media/1", "/dcim/1.jpg", "1.jpg", "image/jpeg", 1234, 1724000000)
	if err != nil || !staged {
		t.Fatalf("BindingStagePendingImport = %v, %v", staged, err)
	}
	if _, err := BindingStagePendingImport("content://media/2", "/dcim/2.mp4", "2.mp4", "video/mp4", 99, 1724000100); err != nil {
		t.Fatal(err)
	}
	if err := BindingSuppressPendingImport("content://media/2"); err != nil {
		t.Fatalf("BindingSuppressPendingImport: %v", err)
	}

	// Process death: the intent is still there after reopening.
	if err := BindingClose(); err != nil {
		t.Fatal(err)
	}
	if err := BindingOpen(dbPath, "http://localhost", "", "test", "test", "localhost"); err != nil {
		t.Fatalf("BindingOpen (reopen): %v", err)
	}

	js, err := BindingListPendingImports(0)
	if err != nil {
		t.Fatalf("BindingListPendingImports: %v", err)
	}
	var list []queue.PendingImport
	if err := json.Unmarshal([]byte(js), &list); err != nil {
		t.Fatalf("decode %s: %v", js, err)
	}
	if len(list) != 1 || list[0].LocalID != "content://media/1" || list[0].LocalPath != "/dcim/1.jpg" ||
		list[0].Filename != "1.jpg" || list[0].MimeType != "image/jpeg" || list[0].SizeBytes != 1234 ||
		list[0].CapturedAtUnix != 1724000000 || list[0].State != queue.PendingImportPending {
		t.Fatalf("pending list = %s", js)
	}
	if sup, err := BindingIsImportSuppressed("content://media/2"); err != nil || !sup {
		t.Fatalf("BindingIsImportSuppressed(2) = %v, %v", sup, err)
	}
	if staged, err := BindingStagePendingImport("content://media/2", "/dcim/2.mp4", "2.mp4", "video/mp4", 99, 1724000100); err != nil || staged {
		t.Fatalf("re-staging a skipped item = %v, %v; want false", staged, err)
	}

	if deleted, err := BindingDeletePendingImport("content://media/1"); err != nil || !deleted {
		t.Fatalf("BindingDeletePendingImport = %v, %v", deleted, err)
	}
	if js, _ := BindingListPendingImports(10); js != "[]" {
		t.Fatalf("list after delete = %s, want []", js)
	}

	for name, err := range map[string]error{
		"stage":    func() error { _, err := BindingStagePendingImport("", "", "", "", 0, 0); return err }(),
		"suppress": BindingSuppressPendingImport(""),
		"delete":   func() error { _, err := BindingDeletePendingImport(""); return err }(),
		"is":       func() error { _, err := BindingIsImportSuppressed(""); return err }(),
	} {
		if got := errorCode(t, err); got != CodeInvalidInput {
			t.Errorf("%s with empty localID: code %s, want INVALID_INPUT", name, got)
		}
	}
}
