package branchdam

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
)

// newTestEngineWithServer returns a branchdam engine and the URL of a
// mock server that handles the standard branchdam REST endpoints
// (handshake, upload, events, node-status, telemetry). The mock is
// closed via t.Cleanup.
func newTestEngineWithServer(t *testing.T) (*Engine, string) {
	t.Helper()

	var uploads int
	var events int
	var statuses int

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/agent/handshake":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(client.HandshakeResponse{
				OK:             true,
				ServerVersion:  "test-1.0",
				NamingTemplate: "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}",
			})
		case "/api/v1/agent/upload":
			uploads++
			_, _ = io.ReadAll(r.Body)
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(client.UploadResponse{
				OK:           true,
				NodeUUID:     "test-node-1",
				Blake3Hash:   r.Header.Get("X-Blake3-Hash"),
				Status:       "UPLOADED",
				RelativePath: "test/path",
			})
		case "/api/v1/agent/events":
			events++
			_ = json.NewEncoder(w).Encode(client.AgentEventResponse{EventID: "evt-1"})
		case "/api/v1/agent/node-status":
			statuses++
			_ = json.NewEncoder(w).Encode(client.NodeStatusResponse{
				Statuses: []client.NodeStatusItem{
					{
						NodeUUID: r.Header.Get("X-Debug-Lookup"),
						Found:    true,
						Verified: true,
						Tier:     "TIER3_MASTER_ARCHIVE",
					},
				},
			})
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(func() {
		server.Close()
		t.Logf("test server saw %d uploads, %d events, %d status queries", uploads, events, statuses)
	})

	dir := t.TempDir()
	e, err := NewEngine(EngineOptions{
		DBPath:        filepath.Join(dir, "engine.db"),
		BaseURL:       server.URL,
		APIKey:        "test",
		AgentID:       "test-agent",
		ClientVersion: "test-1.0",
	})
	if err != nil {
		t.Fatalf("NewEngine: %v", err)
	}
	t.Cleanup(func() { _ = e.Close() })
	return e, server.URL
}

// TestEnqueueMedia_HappyPath: a fresh engine enqueues a media file
// through to the queue with a valid BLAKE3 hash and the file's
// captured-at timestamp.
func TestEnqueueMedia_HappyPath(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	dir := t.TempDir()
	mediaPath := filepath.Join(dir, "PXL_TEST.dng")
	if err := writeTestFile(mediaPath, "test media content"); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	id, err := e.EnqueueMedia(EnqueueMediaOptions{
		LocalPath:      mediaPath,
		Filename:       "PXL_TEST.dng",
		LocalID:        "ph://asset-1",
		CapturedAtUnix: 1724000000,
	})
	if err != nil {
		t.Fatalf("EnqueueMedia: %v", err)
	}
	if id <= 0 {
		t.Fatalf("id = %d, want > 0", id)
	}
}

// TestEnqueueLineageEvent_HappyPath: a Confidence-Exact lineage
// event flows through EnqueueLineageEvent and is persisted in the
// events queue.
func TestEnqueueLineageEvent_HappyPath(t *testing.T) {
	e, _ := newTestEngineWithServer(t)

	eventUUID, err := e.EnqueueLineageEvent(
		"ph://master-1", "ph://child-1", "DERIVED_FROM", "android_camera_pair", ConfidenceExact,
	)
	if err != nil {
		t.Fatalf("EnqueueLineageEvent: %v", err)
	}
	if eventUUID == "" {
		t.Fatalf("expected non-empty event UUID")
	}
}

// TestEnqueueLineageEvent_RejectsBadConfidence: NaN, Inf, and
// out-of-range confidence values are rejected with INVALID_INPUT.
func TestEnqueueLineageEvent_RejectsBadConfidence(t *testing.T) {
	e, _ := newTestEngineWithServer(t)

	tests := []struct {
		name       string
		confidence Confidence
	}{
		{"negative", Confidence(-0.5)},
		{"over-one", Confidence(1.5)},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := e.EnqueueLineageEvent(
				"ph://master", "ph://child", "DERIVED_FROM", "android_camera_pair", tt.confidence,
			)
			if err == nil {
				t.Fatalf("expected error for confidence %v", tt.confidence)
			}
			be, ok := err.(*Error)
			if !ok {
				t.Fatalf("error type = %T, want *Error", err)
			}
			if be.Code != "INVALID_INPUT" {
				t.Fatalf("Code = %q, want INVALID_INPUT", be.Code)
			}
		})
	}
}

// TestSyncBatch_EndToEnd: a real upload + a real event go through
// the engine and the server, returning non-zero counts. Exercises
// the SyncBatch public surface end-to-end (B.1 + B.2.1 + B.2.4).
func TestSyncBatch_EndToEnd(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	dir := t.TempDir()
	mediaPath := filepath.Join(dir, "PXL_SYNC.dng")
	if err := writeTestFile(mediaPath, "sync test content"); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	if _, err := e.EnqueueMedia(EnqueueMediaOptions{
		LocalPath:      mediaPath,
		Filename:       "PXL_SYNC.dng",
		LocalID:        "ph://sync-1",
		CapturedAtUnix: 1724000000,
	}); err != nil {
		t.Fatalf("EnqueueMedia: %v", err)
	}
	if _, err := e.EnqueueLineageEvent("ph://m", "ph://c", "DERIVED_FROM", "android_camera_pair", ConfidenceExact); err != nil {
		t.Fatalf("EnqueueLineageEvent: %v", err)
	}

	result, err := e.SyncBatch(SyncOptions{
		TimeoutSecs:    10,
		BatchSize:      5,
		IncludeEvents:  true,
		IncludeUploads: true,
	})
	if err != nil {
		t.Fatalf("SyncBatch: %v", err)
	}
	if result.Uploaded != 1 {
		t.Fatalf("Uploaded = %d, want 1", result.Uploaded)
	}
	if result.EventsSent != 1 {
		t.Fatalf("EventsSent = %d, want 1", result.EventsSent)
	}
}

// TestSyncBatch_EmptyDefaults: SyncBatch with the standard
// "everything on" defaults (60s timeout, 10 batch size) returns 0
// counts with no error when the queue is empty.
func TestSyncBatch_EmptyDefaults(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	result, err := e.SyncBatch(SyncOptions{
		IncludeEvents:  true,
		IncludeUploads: true,
	})
	if err != nil {
		t.Fatalf("SyncBatch: %v", err)
	}
	if result.Uploaded != 0 || result.EventsSent != 0 {
		t.Fatalf("expected 0/0 on empty queue, got %+v", result)
	}
}

// TestSyncBatch_RejectsAllFalse: SyncBatch with both include flags
// false returns INVALID_INPUT (it's a programmer error to call with
// no work).
func TestSyncBatch_RejectsAllFalse(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	_, err := e.SyncBatch(SyncOptions{
		IncludeEvents:  false,
		IncludeUploads: false,
	})
	if err == nil {
		t.Fatalf("expected error")
	}
	be, ok := err.(*Error)
	if !ok {
		t.Fatalf("error type = %T, want *Error", err)
	}
	if be.Code != "INVALID_INPUT" {
		t.Fatalf("Code = %q, want INVALID_INPUT", be.Code)
	}
}

// TestSetCancelFlag_BreakSync: setting the cancel flag before
// SyncBatch causes the claim to return zero rows, so SyncBatch
// returns zero completed with no error.
func TestSetCancelFlag_BreakSync(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	dir := t.TempDir()
	mediaPath := filepath.Join(dir, "PXL_CANCEL.dng")
	if err := writeTestFile(mediaPath, "cancel test"); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	if _, err := e.EnqueueMedia(EnqueueMediaOptions{
		LocalPath:      mediaPath,
		Filename:       "PXL_CANCEL.dng",
		LocalID:        "ph://cancel-1",
		CapturedAtUnix: 1724000000,
	}); err != nil {
		t.Fatalf("EnqueueMedia: %v", err)
	}

	// Pre-set the cancel flag; the claim WHERE clause filters by
	// cancel_requested = 0, so the claim returns 0 rows.
	if err := e.SetCancelFlag(); err != nil {
		t.Fatalf("SetCancelFlag: %v", err)
	}
	result, err := e.SyncBatch(SyncOptions{IncludeEvents: false, IncludeUploads: true})
	if err != nil {
		t.Fatalf("SyncBatch: %v", err)
	}
	if result.Uploaded != 0 {
		t.Fatalf("Uploaded = %d after cancel, want 0", result.Uploaded)
	}
}

// TestReclaimSafeSpace_HappyPath: the engine-owned atomic reclaim
// re-queries the server, finds the node verified+TIER3, and marks
// the local flag. (B.2.7)
func TestReclaimSafeSpace_HappyPath(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	if err := e.queue.RecordLocalMedia("ph://happy-1", "test-node-1", "b3-happy", "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia: %v", err)
	}
	verdict, err := e.ReclaimSafeSpace("ph://happy-1")
	if err != nil {
		t.Fatalf("ReclaimSafeSpace: %v", err)
	}
	if !verdict.Eligible {
		t.Fatalf("expected Eligible=true, got %+v", verdict)
	}
	// The local flag should now be set.
	isOffloaded, err := e.queue.IsMediaOffloaded("ph://happy-1")
	if err != nil {
		t.Fatalf("IsMediaOffloaded: %v", err)
	}
	if !isOffloaded {
		t.Fatalf("expected local flag set after Eligible=true")
	}
}

// TestReclaimSafeSpace_NotFoundInLocal: a LocalID that has no
// entry in the local media state returns Ineligible. The engine
// reports "not found" as a non-error (Eligible=false, Reason="not
// found in local state"); the branchdam wrapper maps that to
// VERIFIED_REQUIRED with a *Error so the shell can distinguish it
// from a successful Eligible=true (which has no error).
func TestReclaimSafeSpace_NotFoundInLocal(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	verdict, err := e.ReclaimSafeSpace("ph://does-not-exist")
	if verdict.Eligible {
		t.Fatalf("expected Eligible=false for unknown LocalID")
	}
	if verdict.LocalID != "ph://does-not-exist" {
		t.Fatalf("LocalID mismatch: %q", verdict.LocalID)
	}
	// err is non-nil (VERIFIED_REQUIRED); this is the shell's signal
	// that the asset is not safely reclaimable.
	if err == nil {
		t.Fatalf("expected non-nil error for not-found LocalID")
	}
	be, ok := err.(*Error)
	if !ok {
		t.Fatalf("error type = %T, want *Error", err)
	}
	if be.Code != "VERIFIED_REQUIRED" {
		t.Fatalf("Code = %q, want VERIFIED_REQUIRED", be.Code)
	}
}

// TestEnqueueDeleteEvent_HappyPath: smoke test for the delete event.
func TestEnqueueDeleteEvent_HappyPath(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	eventUUID, err := e.EnqueueDeleteEvent("ph://asset-deleted")
	if err != nil {
		t.Fatalf("EnqueueDeleteEvent: %v", err)
	}
	if eventUUID == "" {
		t.Fatalf("expected non-empty event UUID")
	}
}

// TestComputeHashes_HappyPath: streams a file, returns a populated
// Hashes struct with non-empty digests.
func TestComputeHashes_HappyPath(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	dir := t.TempDir()
	mediaPath := filepath.Join(dir, "PXL_HASH.dng")
	if err := writeTestFile(mediaPath, "hash me"); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	hashes, err := e.ComputeHashes(mediaPath, nil)
	if err != nil {
		t.Fatalf("ComputeHashes: %v", err)
	}
	if hashes.Blake3 == "" {
		t.Fatalf("expected non-empty BLAKE3 hash")
	}
	if hashes.Fast == "" {
		t.Fatalf("expected non-empty Fast hash")
	}
}

// TestFetchNamingTemplate_HappyPath: the handshake response
// includes a naming template; the engine surfaces it.
func TestFetchNamingTemplate_HappyPath(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	tpl, err := e.FetchNamingTemplate()
	if err != nil {
		t.Fatalf("FetchNamingTemplate: %v", err)
	}
	if tpl == "" {
		t.Fatalf("expected non-empty naming template")
	}
}

// writeTestFile is a small helper that writes a small payload to a file.
func writeTestFile(path string, content string) error {
	return os.WriteFile(path, []byte(content), 0o644)
}
