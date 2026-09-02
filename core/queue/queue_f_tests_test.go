package queue

import (
	"os"
	"path/filepath"
	"testing"
)

// writeFile is a tiny helper used by the recovery-error test.
func writeFile(path string, data []byte) error {
	return os.WriteFile(path, data, 0o644)
}

// TestClaimPendingUploads_ReclaimStuckINPROGRESS: an item left in
// IN_PROGRESS from a previous session is recovered to PENDING by
// Queue.Open (startup recovery), and then becomes claimable.
func TestClaimPendingUploads_ReclaimStuckINPROGRESS(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "test_queue.db")

	// First session: enqueue, mark in progress, then close.
	q1, err := Open(dbPath)
	if err != nil {
		t.Fatalf("Open session 1: %v", err)
	}
	id, err := q1.EnqueueUpload(&UploadItem{
		LocalPath:      "/sdcard/DCIM/Camera/stuck.dng",
		TargetFilename: "stuck.dng",
		Blake3Hash:     "b3stuck",
	})
	if err != nil {
		t.Fatalf("EnqueueUpload: %v", err)
	}
	if err := q1.MarkUploadInProgress(id); err != nil {
		t.Fatalf("MarkUploadInProgress: %v", err)
	}
	// Simulate a crash: close without marking complete.
	if err := q1.Close(); err != nil {
		t.Fatalf("Close session 1: %v", err)
	}

	// Second session: Open should run the recovery UPDATE and reset
	// the stuck IN_PROGRESS row back to PENDING.
	q2, err := Open(dbPath)
	if err != nil {
		t.Fatalf("Open session 2: %v", err)
	}
	t.Cleanup(func() { _ = q2.Close() })

	// The recovered item is now claimable.
	claimed, err := q2.ClaimPendingUploads(10, 0, 5)
	if err != nil {
		t.Fatalf("ClaimPendingUploads: %v", err)
	}
	if len(claimed) != 1 {
		t.Fatalf("expected 1 claimed item after recovery, got %d", len(claimed))
	}
	if claimed[0].ID != id {
		t.Fatalf("claimed id = %d, want %d", claimed[0].ID, id)
	}
}

// TestQueueOpen_RecoveryError: a corrupted SQLite file surfaces an
// error from Open. The recovery UPDATE is best-effort, but the
// schema migration failure must propagate.
func TestQueueOpen_RecoveryError(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "test_queue.db")

	// Write garbage to the DB path so the schema migration fails.
	if err := writeFile(dbPath, []byte("not a sqlite database")); err != nil {
		t.Fatalf("writeFile: %v", err)
	}

	q, err := Open(dbPath)
	if err == nil {
		_ = q.Close()
		t.Fatalf("expected error opening corrupted db, got nil")
	}
}

// TestClaimPendingUploads_HonorsRetryBackoff: an item whose
// last_attempt_unix is recent is not re-claimed within the
// retry-backoff window.
func TestClaimPendingUploads_HonorsRetryBackoff(t *testing.T) {
	q := newTestQueue(t)

	id, err := q.EnqueueUpload(&UploadItem{
		LocalPath:      "/sdcard/DCIM/Camera/backoff.dng",
		TargetFilename: "backoff.dng",
		Blake3Hash:     "b3backoff",
	})
	if err != nil {
		t.Fatalf("EnqueueUpload: %v", err)
	}
	if err := q.MarkUploadFailed(id, "transient", 3); err != nil {
		t.Fatalf("MarkUploadFailed: %v", err)
	}

	// With a 60-second backoff, the item should not be claimable.
	backoff, err := q.ClaimPendingUploads(10, 60, 3)
	if err != nil {
		t.Fatalf("ClaimPendingUploads (backoff): %v", err)
	}
	if len(backoff) != 0 {
		t.Fatalf("expected 0 claimed items within backoff, got %d", len(backoff))
	}

	// With backoff=0, the item is immediately re-claimable.
	noBackoff, err := q.ClaimPendingUploads(10, 0, 3)
	if err != nil {
		t.Fatalf("ClaimPendingUploads (no backoff): %v", err)
	}
	if len(noBackoff) != 1 {
		t.Fatalf("expected 1 claimed item without backoff, got %d", len(noBackoff))
	}
	if noBackoff[0].ID != id {
		t.Fatalf("claimed id mismatch: %d != %d", noBackoff[0].ID, id)
	}
}
