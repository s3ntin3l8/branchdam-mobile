package branchdam

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
)

// newTestEngineWithNodeStatus returns an engine and mock server where
// the /api/v1/agent/node-status response is parameterised by the
// nodeUUIDs in the request body. The mock returns per-node status
// based on a map: nodeUUID → NodeStatusItem. The test can configure
// which nodes are "not verified", "wrong tier", etc.
func newTestEngineWithNodeStatus(t *testing.T, statuses map[string]client.NodeStatusItem) (*Engine, *httptest.Server) {
	t.Helper()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/agent/handshake":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(client.HandshakeResponse{
				OK:             true,
				ServerVersion:  "test-1.0",
				NamingTemplate: "{yyyy}/{mm}/{dd}/{original_name}",
			})
		case "/api/v1/agent/upload":
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
			_ = json.NewEncoder(w).Encode(client.AgentEventResponse{EventID: "evt-1"})
		case "/api/v1/agent/node-status":
			var req client.NodeStatusRequest
			if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			out := []client.NodeStatusItem{}
			for _, uuid := range req.NodeUUIDs {
				if st, ok := statuses[uuid]; ok {
					out = append(out, st)
				}
			}
			_ = json.NewEncoder(w).Encode(client.NodeStatusResponse{Statuses: out})
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)

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
	return e, server
}

// TestIsMediaOffloaded_True: an asset that has been marked offloaded
// (via ReclaimSafeSpace or SetMediaOffloaded) returns true from
// IsMediaOffloaded.
func TestIsMediaOffloaded_True(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	if err := e.queue.RecordLocalMedia("ph://offloaded-1", "test-node-1", "b3-offloaded-1", "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia: %v", err)
	}
	if err := e.SetMediaOffloaded("ph://offloaded-1", true); err != nil {
		t.Fatalf("SetMediaOffloaded: %v", err)
	}
	got, err := e.IsMediaOffloaded("ph://offloaded-1")
	if err != nil {
		t.Fatalf("IsMediaOffloaded: %v", err)
	}
	if !got {
		t.Fatalf("expected IsMediaOffloaded=true after SetMediaOffloaded(true)")
	}
}

// TestIsMediaOffloaded_False: an asset that is in the local state but
// not marked offloaded returns false.
func TestIsMediaOffloaded_False(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	if err := e.queue.RecordLocalMedia("ph://active-1", "test-node-1", "b3-active-1", "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia: %v", err)
	}
	got, err := e.IsMediaOffloaded("ph://active-1")
	if err != nil {
		t.Fatalf("IsMediaOffloaded: %v", err)
	}
	if got {
		t.Fatalf("expected IsMediaOffloaded=false for ACTIVE asset")
	}
}

// TestReclaimSafeSpace_NotVerified: the server reports the node as
// Found but not Verified. The engine returns Eligible=false with
// Reason="not verified on server" and a *Error{Code: VERIFIED_REQUIRED}.
func TestReclaimSafeSpace_NotVerified(t *testing.T) {
	e, _ := newTestEngineWithNodeStatus(t, map[string]client.NodeStatusItem{
		"unverified-node": {
			NodeUUID: "unverified-node",
			Found:    true,
			Verified: false,
			Tier:     "TIER3_MASTER_ARCHIVE",
		},
	})
	if err := e.queue.RecordLocalMedia("ph://unverified-1", "unverified-node", "b3-unv-1", "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia: %v", err)
	}
	verdict, err := e.ReclaimSafeSpace("ph://unverified-1")
	if err == nil {
		t.Fatalf("expected error for unverified node")
	}
	if verdict.Eligible {
		t.Fatalf("expected Eligible=false, got %+v", verdict)
	}
	be, ok := err.(*Error)
	if !ok {
		t.Fatalf("error type = %T, want *Error", err)
	}
	if be.Code != "VERIFIED_REQUIRED" {
		t.Fatalf("Code = %q, want VERIFIED_REQUIRED", be.Code)
	}
	if !contains(verdict.Reason, "not verified") {
		t.Fatalf("Reason = %q, want substring 'not verified'", verdict.Reason)
	}
}

// TestReclaimSafeSpace_TierIneligible: the server reports the node
// as Found + Verified but on an ineligible tier (TIER1). The engine
// returns Eligible=false with Reason containing "tier ineligible".
func TestReclaimSafeSpace_TierIneligible(t *testing.T) {
	e, _ := newTestEngineWithNodeStatus(t, map[string]client.NodeStatusItem{
		"tier1-node": {
			NodeUUID: "tier1-node",
			Found:    true,
			Verified: true,
			Tier:     "TIER1_HOT_CACHE",
		},
	})
	if err := e.queue.RecordLocalMedia("ph://tier1-1", "tier1-node", "b3-tier1-1", "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia: %v", err)
	}
	verdict, err := e.ReclaimSafeSpace("ph://tier1-1")
	if err == nil {
		t.Fatalf("expected error for tier-ineligible node")
	}
	if verdict.Eligible {
		t.Fatalf("expected Eligible=false, got %+v", verdict)
	}
	be, ok := err.(*Error)
	if !ok {
		t.Fatalf("error type = %T, want *Error", err)
	}
	if be.Code != "VERIFIED_REQUIRED" {
		t.Fatalf("Code = %q, want VERIFIED_REQUIRED", be.Code)
	}
	if !contains(verdict.Reason, "tier ineligible") {
		t.Fatalf("Reason = %q, want substring 'tier ineligible'", verdict.Reason)
	}
}

// TestReclaimSafeSpace_TransientNetworkError: the server returns a
// 500 Internal Server Error to node-status. The engine wraps this
// as a client.ClientError with CodeNetworkError. The branchdam
// wrapper maps this to INTERNAL (transient) rather than
// VERIFIED_REQUIRED, so the shell can distinguish a server outage
// from a genuine ineligible verdict.
func TestReclaimSafeSpace_TransientNetworkError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/agent/handshake":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(client.HandshakeResponse{
				OK:            true,
				ServerVersion: "test-1.0",
			})
		case "/api/v1/agent/node-status":
			// Simulate a server-side error. The engine treats this as
			// a network-level failure (the client returns ClientError
			// with CodeNetworkError for non-2xx responses).
			w.WriteHeader(http.StatusInternalServerError)
			_, _ = w.Write([]byte("server overloaded"))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)

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

	if err := e.queue.RecordLocalMedia("ph://transient-1", "transient-node", "b3-transient-1", "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia: %v", err)
	}

	v, err := e.ReclaimSafeSpace("ph://transient-1")
	if v.Eligible {
		t.Fatalf("expected Eligible=false on server error, got %+v", v)
	}
	if err == nil {
		t.Fatalf("expected non-nil error on server 500, got nil")
	}
	be, ok := err.(*Error)
	if !ok {
		t.Fatalf("error type = %T, want *Error", err)
	}
	// Transient network errors should map to INTERNAL, not
	// VERIFIED_REQUIRED. The shell can then distinguish "try
	// again later" from "this node is not verified".
	if be.Code == CodeVerifiedRequired {
		t.Fatalf("Code = %q, want INTERNAL (transient network error, not ineligible verdict)", be.Code)
	}
}

// TestReclaimSafeSpace_NoRecordOnServer: the server responds to
// node-status with an empty list (the nodeUUID is unknown to the
// server). The engine treats this as "server has no record of node"
// and returns Eligible=false with a non-nil error mapped to
// VERIFIED_REQUIRED.
//
// Note: this does NOT exercise a server hang or the engine's 30s
// HTTP client timeout (client.go:33). For a true timeout test, see
// the suggestion in the F#62 audit follow-up; the engine's timeout
// path requires a server that never responds and a test runtime
// measured in seconds, which is impractical for unit tests. The
// real 30s timeout is covered by integration tests on a real
// device with a hung server.
func TestReclaimSafeSpace_NoRecordOnServer(t *testing.T) {
	// Server that responds immediately with an empty
	// NodeStatusResponse (no status for the requested nodeUUID).
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/agent/handshake":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(client.HandshakeResponse{
				OK:            true,
				ServerVersion: "test-1.0",
			})
		case "/api/v1/agent/node-status":
			// Empty list: server has no record of the requested node.
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(client.NodeStatusResponse{})
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)

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

	if err := e.queue.RecordLocalMedia("ph://unknown-1", "unknown-node", "b3-unknown-1", "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia: %v", err)
	}

	v, err := e.ReclaimSafeSpace("ph://unknown-1")
	if v.Eligible {
		t.Fatalf("expected Eligible=false on empty server response, got %+v", v)
	}
	// Empty list is treated as "no record" — the engine returns
	// Eligible=false and a non-nil error mapped to VERIFIED_REQUIRED.
	if err == nil {
		t.Fatalf("expected non-nil error on empty server response, got nil")
	}
	be, ok := err.(*Error)
	if !ok {
		t.Fatalf("error type = %T, want *Error", err)
	}
	if be.Code != CodeVerifiedRequired {
		t.Fatalf("Code = %q, want %q", be.Code, CodeVerifiedRequired)
	}
}

// TestSetCancelFlag_FlipsBothQueues: with uploads AND events pending,
// calling SetCancelFlag before SyncBatch causes the first sync call
// (SyncUploads) to consume the flag and return 0. The events sync
// that follows sees the flag already reset (Swap(false) is a
// capture-and-reset), so events proceed normally.
//
// This documents the per-batch semantics: one SetCancelFlag cancels
// one sync call. A second SetCancelFlag would cancel the next sync.
func TestSetCancelFlag_FlipsBothQueues(t *testing.T) {
	e, _ := newTestEngineWithServer(t)

	// Enqueue an upload.
	dir := t.TempDir()
	mediaPath := filepath.Join(dir, "PXL_CANCEL_UPLOAD.dng")
	if err := writeTestFile(mediaPath, "cancel upload test"); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	if _, err := e.EnqueueMedia(EnqueueMediaOptions{
		LocalPath:      mediaPath,
		Filename:       "PXL_CANCEL_UPLOAD.dng",
		LocalID:        "ph://cancel-upload-1",
		CapturedAtUnix: 1724000000,
	}); err != nil {
		t.Fatalf("EnqueueMedia: %v", err)
	}

	// Enqueue an event.
	if _, err := e.EnqueueLineageEvent("ph://m", "ph://c", "DERIVED_FROM", "android_camera_pair", ConfidenceExact); err != nil {
		t.Fatalf("EnqueueLineageEvent: %v", err)
	}

	// Set cancel flag before SyncBatch.
	if err := e.SetCancelFlag(); err != nil {
		t.Fatalf("SetCancelFlag: %v", err)
	}

	// SyncBatch runs SyncUploads first, which consumes the cancel
	// flag and returns 0. SyncEvents then runs and sees the flag
	// already reset, so it processes the pending event.
	result, err := e.SyncBatch(SyncOptions{IncludeEvents: true, IncludeUploads: true})
	if err != nil {
		t.Fatalf("SyncBatch: %v", err)
	}
	if result.Uploaded != 0 {
		t.Fatalf("Uploaded = %d, want 0 (first sync consumed the cancel flag)", result.Uploaded)
	}
	// Events are not cancelled because the flag was already consumed
	// by SyncUploads. This is by design: the cancel flag is
	// per-batch, and a single SetCancelFlag only affects the first
	// sync call.
	if result.EventsSent != 1 {
		t.Fatalf("EventsSent = %d, want 1 (events sync runs after flag consumed)", result.EventsSent)
	}
}

// TestSetCancelFlag_ResetOnClaim: after SetCancelFlag is observed
// and the flag is reset, the next SyncBatch proceeds normally and
// processes the pending items.
func TestSetCancelFlag_ResetOnClaim(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	dir := t.TempDir()
	mediaPath := filepath.Join(dir, "PXL_RESET.dng")
	if err := writeTestFile(mediaPath, "reset test"); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	if _, err := e.EnqueueMedia(EnqueueMediaOptions{
		LocalPath:      mediaPath,
		Filename:       "PXL_RESET.dng",
		LocalID:        "ph://reset-1",
		CapturedAtUnix: 1724000000,
	}); err != nil {
		t.Fatalf("EnqueueMedia: %v", err)
	}

	// First sync: cancel flag is set, so Uploaded=0.
	if err := e.SetCancelFlag(); err != nil {
		t.Fatalf("SetCancelFlag: %v", err)
	}
	r1, err := e.SyncBatch(SyncOptions{IncludeEvents: false, IncludeUploads: true})
	if err != nil {
		t.Fatalf("SyncBatch 1: %v", err)
	}
	if r1.Uploaded != 0 {
		t.Fatalf("first SyncBatch Uploaded = %d, want 0 (cancel)", r1.Uploaded)
	}

	// Second sync: flag was reset at the top of SyncUploads, so the
	// pending item is processed.
	r2, err := e.SyncBatch(SyncOptions{IncludeEvents: false, IncludeUploads: true})
	if err != nil {
		t.Fatalf("SyncBatch 2: %v", err)
	}
	if r2.Uploaded != 1 {
		t.Fatalf("second SyncBatch Uploaded = %d, want 1 (flag reset)", r2.Uploaded)
	}
}

// contains is a small helper to avoid importing strings just for
// substring checks.
func contains(haystack, needle string) bool {
	if len(needle) == 0 {
		return true
	}
	for i := 0; i+len(needle) <= len(haystack); i++ {
		if haystack[i:i+len(needle)] == needle {
			return true
		}
	}
	return false
}
