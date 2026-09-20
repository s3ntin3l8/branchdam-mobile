package branchdam

import (
	"crypto/rand"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
)

// TestBindingComputeHashes_HasFile verifies that BindingComputeHashes returns
// a stable 64-hex-char BLAKE3 digest for a known file. The hash must match
// what Engine.ComputeHashes returns through the typed API, since both go
// through hasher.HashReader (T2-7 post-copy verify path).
func TestBindingComputeHashes_HasFile(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "engine.db")
	if err := BindingOpen(dbPath, "http://localhost", "", "test", "test", "localhost"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	defer BindingClose()

	srcPath := filepath.Join(dir, "src.bin")
	payload := make([]byte, 4096)
	if _, err := rand.Read(payload); err != nil {
		t.Fatalf("rand.Read: %v", err)
	}
	if err := os.WriteFile(srcPath, payload, 0o644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	got1, err := BindingComputeHashes(srcPath)
	if err != nil {
		t.Fatalf("BindingComputeHashes: %v", err)
	}
	if len(got1) != 64 {
		t.Fatalf("hash length = %d, want 64 hex chars (got %q)", len(got1), got1)
	}

	got2, err := BindingComputeHashes(srcPath)
	if err != nil {
		t.Fatalf("BindingComputeHashes (repeat): %v", err)
	}
	if got1 != got2 {
		t.Fatalf("hash not stable across calls: %q vs %q", got1, got2)
	}
}

// TestBindingComputeHashes_RequiresOpenEngine: the binding must reject
// calls before BindingOpen has been called (the engine state is what
// holds the SQLite DB handle and HTTP client).
func TestBindingComputeHashes_RequiresOpenEngine(t *testing.T) {
	// Ensure no engine is open by closing anything left over from prior tests.
	_ = BindingClose()
	_, err := BindingComputeHashes("/nonexistent")
	if err == nil {
		t.Fatalf("BindingComputeHashes without open engine: expected error, got nil")
	}
}

// TestBindingComputeHashes_EmptyPath: validation should reject empty path
// rather than hashing whatever happens to be at the OS-default location.
func TestBindingComputeHashes_EmptyPath(t *testing.T) {
	dir := t.TempDir()
	if err := BindingOpen(filepath.Join(dir, "engine.db"), "http://localhost", "", "test", "test", "localhost"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	defer BindingClose()
	if _, err := BindingComputeHashes(""); err == nil {
		t.Fatalf("BindingComputeHashes empty path: expected error, got nil")
	}
}

// TestBindingLookupBlake3ForLocalID_Unknown: looking up a localID that has
// never been ingested returns "", not an error. This is the common path
// during a fresh OTG scan where every candidate's localID is new to the
// queue (T2-7 prior-hash warning path).
func TestBindingLookupBlake3ForLocalID_Unknown(t *testing.T) {
	dir := t.TempDir()
	if err := BindingOpen(filepath.Join(dir, "engine.db"), "http://localhost", "", "test", "test", "localhost"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	defer BindingClose()
	hash, err := BindingLookupBlake3ForLocalID("never-seen-before")
	if err != nil {
		t.Fatalf("BindingLookupBlake3ForLocalID: %v", err)
	}
	if hash != "" {
		t.Fatalf("hash for unknown localID = %q, want empty", hash)
	}
}

// TestBindingLookupBlake3ForLocalID_EmptyReturnsEmpty: the empty localID is
// the "no prior ingest possible" sentinel; must not hit the DB.
func TestBindingLookupBlake3ForLocalID_EmptyReturnsEmpty(t *testing.T) {
	dir := t.TempDir()
	if err := BindingOpen(filepath.Join(dir, "engine.db"), "http://localhost", "", "test", "test", "localhost"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	defer BindingClose()
	hash, err := BindingLookupBlake3ForLocalID("")
	if err != nil {
		t.Fatalf("BindingLookupBlake3ForLocalID empty: %v", err)
	}
	if hash != "" {
		t.Fatalf("hash for empty localID = %q, want empty", hash)
	}
}

// TestBindingLookupBlake3ForLocalID_RoundTrip: after RecordLocalMedia
// stores a hash, the binding returns it back. This is the success path
// the OTG ingest pipeline uses to detect "same localID, different bytes"
// scenarios (typically a failing SD card mid-scan).
func TestBindingLookupBlake3ForLocalID_RoundTrip(t *testing.T) {
	dir := t.TempDir()
	if err := BindingOpen(filepath.Join(dir, "engine.db"), "http://localhost", "", "test", "test", "localhost"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	defer BindingClose()

	const localID = "test-local-id-001"
	const want = "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262" // pragma: allowlist secret
	if err := bindingEngine.queue.RecordLocalMedia(localID, "", want, "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia: %v", err)
	}

	got, err := BindingLookupBlake3ForLocalID(localID)
	if err != nil {
		t.Fatalf("BindingLookupBlake3ForLocalID: %v", err)
	}
	if got != want {
		t.Fatalf("hash = %q, want %q", got, want)
	}
}

// TestBindingEnqueueMediaWithSourceHash verifies that BindingEnqueueMediaWithSourceHash
// stores the explicit sourcePathHash into the upload queue, and BindingEnqueueMedia
// falls back to hashing the local path.
func TestBindingEnqueueMediaWithSourceHash(t *testing.T) {
	dir := t.TempDir()
	if err := BindingOpen(filepath.Join(dir, "engine.db"), "http://localhost", "", "test", "test", "localhost"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	defer BindingClose()

	p1 := filepath.Join(dir, "sample1.jpg")
	if err := os.WriteFile(p1, []byte("sample1"), 0o644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	validHash := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" // pragma: allowlist secret
	id1, err := BindingEnqueueMediaWithSourceHash(p1, "sample1.jpg", "local-1", "Canon-R5", validHash, 1724000000, 7)
	if err != nil {
		t.Fatalf("BindingEnqueueMediaWithSourceHash: %v", err)
	}
	if id1 <= 0 {
		t.Fatalf("expected id > 0, got %d", id1)
	}

	var srcHash1 string
	if err := bindingEngine.queue.DB().QueryRow("SELECT source_path_hash FROM upload_queue WHERE id = ?", id1).Scan(&srcHash1); err != nil {
		t.Fatalf("query source_path_hash 1: %v", err)
	}
	if srcHash1 != validHash {
		t.Fatalf("SourcePathHash = %q, want %q", srcHash1, validHash)
	}

	p2 := filepath.Join(dir, "sample2.jpg")
	if err := os.WriteFile(p2, []byte("sample2"), 0o644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	id2, err := BindingEnqueueMedia(p2, "sample2.jpg", "local-2", "Canon-R5", 1724000000, 7)
	if err != nil {
		t.Fatalf("BindingEnqueueMedia: %v", err)
	}
	if id2 <= 0 {
		t.Fatalf("expected id > 0, got %d", id2)
	}

	var srcHash2 string
	if err := bindingEngine.queue.DB().QueryRow("SELECT source_path_hash FROM upload_queue WHERE id = ?", id2).Scan(&srcHash2); err != nil {
		t.Fatalf("query source_path_hash 2: %v", err)
	}
	if srcHash2 == "" || srcHash2 == validHash {
		t.Fatalf("expected fallback SourcePathHash, got %q", srcHash2)
	}

	// Invalid sourcePathHash rejected at binding entry
	if _, err := BindingEnqueueMediaWithSourceHash(p1, "sample1.jpg", "local-3", "Canon-R5", "invalid-hash", 1724000000, 7); err == nil {
		t.Fatalf("expected error for invalid sourcePathHash in BindingEnqueueMediaWithSourceHash, got nil")
	}
}

func TestBindingGetMediaStatusAndCountPendingUploads(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "test_status.db")

	if err := BindingOpen(dbPath, "http://localhost", "", "test", "0.2.0", "localhost"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	defer BindingClose()

	// Initial status for un-enqueued item
	status, err := BindingGetMediaStatus("local-test-1")
	if err != nil {
		t.Fatalf("BindingGetMediaStatus: %v", err)
	}
	if status != "NOT_ENQUEUED" {
		t.Fatalf("expected NOT_ENQUEUED, got %q", status)
	}

	count, err := BindingCountPendingUploads()
	if err != nil {
		t.Fatalf("BindingCountPendingUploads: %v", err)
	}
	if count != 0 {
		t.Fatalf("expected 0 pending, got %d", count)
	}

	// Enqueue item
	sampleFile := filepath.Join(dir, "sample.jpg")
	if err := os.WriteFile(sampleFile, []byte("sample bytes"), 0o644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	id, err := BindingEnqueueMedia(sampleFile, "sample.jpg", "local-test-1", "Pixel", 1724000000, 12)
	if err != nil || id <= 0 {
		t.Fatalf("BindingEnqueueMedia failed: %v, id=%d", err, id)
	}

	status, err = BindingGetMediaStatus("local-test-1")
	if err != nil {
		t.Fatalf("BindingGetMediaStatus after enqueue: %v", err)
	}
	if status != "PENDING" {
		t.Fatalf("expected PENDING, got %q", status)
	}

	count, err = BindingCountPendingUploads()
	if err != nil {
		t.Fatalf("BindingCountPendingUploads: %v", err)
	}
	if count != 1 {
		t.Fatalf("expected 1 pending, got %d", count)
	}

	// Mark completed
	if err := bindingEngine.queue.MarkUploadComplete(id, "node-uuid-123"); err != nil {
		t.Fatalf("MarkUploadComplete: %v", err)
	}

	status, err = BindingGetMediaStatus("local-test-1")
	if err != nil {
		t.Fatalf("BindingGetMediaStatus after complete: %v", err)
	}
	if status != "COMPLETED" {
		t.Fatalf("expected COMPLETED, got %q", status)
	}

	count, err = BindingCountPendingUploads()
	if err != nil {
		t.Fatalf("BindingCountPendingUploads: %v", err)
	}
	if count != 0 {
		t.Fatalf("expected 0 pending after completion, got %d", count)
	}
}

func TestBindingGetAllMediaStatuses(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "test_all_statuses.db")

	if err := BindingOpen(dbPath, "http://localhost", "", "test", "0.2.0", "localhost"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	defer BindingClose()

	sampleFile := filepath.Join(dir, "status.jpg")
	if err := os.WriteFile(sampleFile, []byte("status bytes"), 0o644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	id, err := BindingEnqueueMedia(sampleFile, "status.jpg", "local-status-1", "Pixel", 1724000000, 12)
	if err != nil || id <= 0 {
		t.Fatalf("BindingEnqueueMedia failed: %v", err)
	}

	jsonStr, err := BindingGetAllMediaStatuses()
	if err != nil {
		t.Fatalf("BindingGetAllMediaStatuses failed: %v", err)
	}
	if jsonStr == "" || jsonStr == "{}" {
		t.Fatalf("expected non-empty JSON string, got %q", jsonStr)
	}
}

func TestBindingOffloadAndReclaimAndLineage(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "test_offload_reclaim.db")

	if err := BindingOpen(dbPath, "http://localhost", "", "test", "0.2.0", "localhost"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	defer BindingClose()

	// Offload flag
	offloaded, err := BindingIsMediaOffloaded("loc-1")
	if err != nil || offloaded {
		t.Fatalf("expected false for new item offloaded status")
	}

	if err := BindingSetMediaOffloaded("loc-1", true); err != nil {
		t.Fatalf("BindingSetMediaOffloaded failed: %v", err)
	}

	offloadedAfter, err := BindingIsMediaOffloaded("loc-1")
	if err != nil || !offloadedAfter {
		t.Fatalf("expected true for offloaded status")
	}

	// Lineage event
	uuid, err := BindingEnqueueLineageEvent("parent-1", "child-1", "DERIVED_FROM", "test_resolver", 1.0)
	if err != nil || uuid == "" {
		t.Fatalf("BindingEnqueueLineageEvent failed: %v, uuid=%s", err, uuid)
	}

	// Delete event
	delUuid, err := BindingEnqueueDeleteEvent("del-loc-1")
	if err != nil || delUuid == "" {
		t.Fatalf("BindingEnqueueDeleteEvent failed: %v, delUuid=%s", err, delUuid)
	}

	// Candidates check
	verdicts, err := BindingCheckSafeSpaceCandidates("loc-1,loc-2")
	if err != nil {
		t.Fatalf("BindingCheckSafeSpaceCandidates failed: %v", err)
	}
	var parsedVerdicts []SafeSpaceVerdict
	if err := json.Unmarshal([]byte(verdicts), &parsedVerdicts); err != nil {
		t.Fatalf("expected valid JSON verdicts array, got %q: %v", verdicts, err)
	}
	if len(parsedVerdicts) != 2 {
		t.Fatalf("expected 2 verdicts in JSON array, got %d", len(parsedVerdicts))
	}
}

func TestBindingSyncBatchAndCheckContent(t *testing.T) {
	dir := t.TempDir()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/v1/agent/check-content") {
			_ = json.NewEncoder(w).Encode(client.ContentCheckResult{Found: true, NodeUUID: "found-123"})
			return
		}
		if r.URL.Path == "/api/v1/agent/handshake" {
			_ = json.NewEncoder(w).Encode(client.HandshakeResponse{OK: true, NamingTemplate: "tpl"})
			return
		}
		http.NotFound(w, r)
	}))
	defer server.Close()

	if err := BindingOpen(filepath.Join(dir, "engine.db"), server.URL, "key", "test", "0.2.0", "127.0.0.1,localhost"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	defer BindingClose()

	// CheckContent
	resStr, err := BindingCheckContent("fast1", "full1")
	if err != nil || !strings.Contains(resStr, "found-123") {
		t.Fatalf("BindingCheckContent failed: %v, res=%s", err, resStr)
	}

	// Fetch naming template
	tpl, err := BindingFetchNamingTemplate()
	if err != nil || tpl != "tpl" {
		t.Fatalf("BindingFetchNamingTemplate failed: %v, tpl=%s", err, tpl)
	}

	// Cancel flag
	if err := BindingSetCancelFlag(); err != nil {
		t.Fatalf("BindingSetCancelFlag failed: %v", err)
	}

	// Sync batch
	_, _ = BindingSyncBatch(10, 5)
}
