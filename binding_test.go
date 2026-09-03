package branchdam

import (
	"crypto/rand"
	"os"
	"path/filepath"
	"testing"
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
