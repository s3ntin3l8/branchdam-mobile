package queue

import (
	"errors"
	"path/filepath"
	"testing"
)

func newTestQueue(t *testing.T) *Queue {
	t.Helper()
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "test_queue.db")
	q, err := Open(dbPath)
	if err != nil {
		t.Fatalf("failed to open test queue: %v", err)
	}
	t.Cleanup(func() {
		_ = q.Close()
	})
	return q
}

func TestUploadQueueLifecycle(t *testing.T) {
	q := newTestQueue(t)

	item := &UploadItem{
		LocalPath:      "/sdcard/DCIM/Camera/PXL_001.dng",
		TargetFilename: "PXL_001.dng",
		FastHash:       "0123456789abcdef",
		Blake3Hash:     "b3f1c4d9e2a7568013c9a4d2e8f7b1063c5a9d7e2f4b8016938ac1d4e7f2b09a// pragma: allowlist secret",
		SizeBytes:      24000000,
		CapturedAtUnix: 1724000000,
	}

	id, err := q.EnqueueUpload(item)
	if err != nil {
		t.Fatalf("EnqueueUpload failed: %v", err)
	}
	if id <= 0 {
		t.Fatalf("invalid id: %d", id)
	}

	count, err := q.CountPendingUploads()
	if err != nil || count != 1 {
		t.Fatalf("expected count 1, got %d (err: %v)", count, err)
	}

	// Claim
	claimed, err := q.ClaimPendingUploads(10, 5, 3)
	if err != nil || len(claimed) != 1 {
		t.Fatalf("expected 1 claimed item, got %d (err: %v)", len(claimed), err)
	}
	if claimed[0].ID != id {
		t.Fatalf("claimed item ID mismatch: got %d, expected %d", claimed[0].ID, id)
	}

	// In progress
	if err := q.MarkUploadInProgress(id); err != nil {
		t.Fatalf("MarkUploadInProgress failed: %v", err)
	}

	// Mark failed (retry 1)
	if err := q.MarkUploadFailed(id, "connection reset", 3); err != nil {
		t.Fatalf("MarkUploadFailed failed: %v", err)
	}

	// Immediate re-claim should be 0 because of retry backoff (5s)
	claimedBackoff, err := q.ClaimPendingUploads(10, 5, 3)
	if err != nil || len(claimedBackoff) != 0 {
		t.Fatalf("expected 0 claimed items due to backoff, got %d", len(claimedBackoff))
	}

	// Non-backoff claim should return item
	claimedNoBackoff, err := q.ClaimPendingUploads(10, 0, 3)
	if err != nil || len(claimedNoBackoff) != 1 {
		t.Fatalf("expected 1 claimed item without backoff, got %d", len(claimedNoBackoff))
	}
	if claimedNoBackoff[0].RetryCount != 1 {
		t.Fatalf("expected retry count 1, got %d", claimedNoBackoff[0].RetryCount)
	}

	// Mark completed
	if err := q.MarkUploadComplete(id, "018f2345-6789-7abc-def0-123456789abc"); err != nil {
		t.Fatalf("MarkUploadComplete failed: %v", err)
	}

	countAfter, err := q.CountPendingUploads()
	if err != nil || countAfter != 0 {
		t.Fatalf("expected 0 pending uploads after complete, got %d", countAfter)
	}
}

func TestEventQueueLifecycle(t *testing.T) {
	q := newTestQueue(t)

	eventUUID, err := q.EnqueueEvent("EVENT_NODE_CREATED", `{"nodeUuid":"018f...","fastHash":"abc"}`)
	if err != nil {
		t.Fatalf("EnqueueEvent failed: %v", err)
	}
	if eventUUID == "" {
		t.Fatal("expected non-empty eventUUID")
	}

	count, err := q.CountPendingEvents()
	if err != nil || count != 1 {
		t.Fatalf("expected 1 pending event, got %d", count)
	}

	claimed, err := q.ClaimPendingEvents(5, 5, 3)
	if err != nil || len(claimed) != 1 {
		t.Fatalf("expected 1 claimed event, got %d", len(claimed))
	}
	if claimed[0].EventUUID != eventUUID {
		t.Fatalf("eventUUID mismatch: got %q, expected %q", claimed[0].EventUUID, eventUUID)
	}

	// Mark sent
	if err := q.MarkEventSent(claimed[0].ID); err != nil {
		t.Fatalf("MarkEventSent failed: %v", err)
	}

	countAfter, err := q.CountPendingEvents()
	if err != nil || countAfter != 0 {
		t.Fatalf("expected 0 pending events after sent, got %d", countAfter)
	}
}

func TestLocalMediaStateAndOffload(t *testing.T) {
	q := newTestQueue(t)

	localID := "content://media/external/images/media/1004"
	nodeUUID := "018f2345-6789-7abc-def0-123456789abc"
	blake3 := "b3f1c4d9e2a7568013c9a4d2e8f7b1063c5a9d7e2f4b8016938ac1d4e7f2b09a// pragma: allowlist secret"

	if err := q.RecordLocalMedia(localID, nodeUUID, blake3, "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia failed: %v", err)
	}

	state, err := q.GetMediaByLocalID(localID)
	if err != nil {
		t.Fatalf("GetMediaByLocalID failed: %v", err)
	}
	if state.NodeUUID != nodeUUID || state.Blake3Hash != blake3 || state.IsOffloaded {
		t.Fatalf("unexpected state: %+v", state)
	}

	offloaded, err := q.IsMediaOffloaded(localID)
	if err != nil || offloaded {
		t.Fatalf("expected isOffloaded false, got %v", offloaded)
	}

	// Set offloaded
	if err := q.SetMediaOffloaded(localID, true); err != nil {
		t.Fatalf("SetMediaOffloaded failed: %v", err)
	}

	offloadedAfter, err := q.IsMediaOffloaded(localID)
	if err != nil || !offloadedAfter {
		t.Fatalf("expected isOffloaded true, got %v", offloadedAfter)
	}

	// Unknown local ID should return false, nil
	unknownOffloaded, err := q.IsMediaOffloaded("unknown_id")
	if err != nil || unknownOffloaded {
		t.Fatalf("expected false for unknown ID, got %v (err: %v)", unknownOffloaded, err)
	}
}

func TestGetUploadItemByBlake3Hash(t *testing.T) {
	q := newTestQueue(t)

	hash := "b3f1c4d9e2a7568013c9a4d2e8f7b1063c5a9d7e2f4b8016938ac1d4e7f2b09a// pragma: allowlist secret"

	// Non-existent hash should return nil, nil
	item, err := q.GetUploadItemByBlake3Hash(hash)
	if err != nil {
		t.Fatalf("unexpected error querying non-existent hash: %v", err)
	}
	if item != nil {
		t.Fatalf("expected nil item, got %+v", item)
	}

	// Enqueue item
	newItem := &UploadItem{
		LocalPath:      "/sdcard/DCIM/Camera/PXL_100.dng",
		TargetFilename: "PXL_100.dng",
		FastHash:       "fast1234",
		Blake3Hash:     hash,
		CameraModel:    "pixel-fold",
		SizeBytes:      1024,
		CapturedAtUnix: 1724000100,
	}
	id, err := q.EnqueueUpload(newItem)
	if err != nil {
		t.Fatalf("EnqueueUpload failed: %v", err)
	}

	found, err := q.GetUploadItemByBlake3Hash(hash)
	if err != nil {
		t.Fatalf("GetUploadItemByBlake3Hash failed: %v", err)
	}
	if found == nil || found.ID != id || found.Blake3Hash != hash {
		t.Fatalf("found item mismatch: %+v (expected id %d)", found, id)
	}

	// Exhaust retries so item becomes FAILED; GetUploadItemByBlake3Hash should return nil
	if err := MarkUploadExhaustedForTest(q, id); err != nil {
		t.Fatalf("failed to exhaust retries: %v", err)
	}
	notFound, err := q.GetUploadItemByBlake3Hash(hash)
	if err != nil {
		t.Fatalf("unexpected error after item failed: %v", err)
	}
	if notFound != nil {
		t.Fatalf("expected nil for FAILED item, got %+v", notFound)
	}
}

// MarkUploadExhaustedForTest directly sets status to FAILED in the test DB to
// simulate retry exhaustion without coupling to MarkUploadFailed's retry logic.
func MarkUploadExhaustedForTest(q *Queue, id int64) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	_, err := q.db.Exec(`UPDATE upload_queue SET status = 'FAILED', retry_count = 5 WHERE id = ?`, id)
	return err
}

// TestClaimPendingUploads_NoDoubleClaim verifies that the atomic
// UPDATE...RETURNING claim prevents two concurrent goroutines from
// claiming the same row. With the pre-B.2.1 SELECT-then-UPDATE pattern,
// this test would flake; with UPDATE...RETURNING it is deterministic.
func TestClaimPendingUploads_NoDoubleClaim(t *testing.T) {
	q := newTestQueue(t)

	// Enqueue 5 items.
	for i := 0; i < 5; i++ {
		_, err := q.EnqueueUpload(&UploadItem{
			LocalPath:      "/sdcard/DCIM/Camera/file_" + string(rune('a'+i)) + ".dng",
			TargetFilename: "file_" + string(rune('a'+i)) + ".dng",
			Blake3Hash:     "b3" + string(rune('a'+i)),
		})
		if err != nil {
			t.Fatalf("EnqueueUpload: %v", err)
		}
	}

	// Spawn 10 goroutines all calling ClaimPendingUploads with limit=3.
	// Each call should return a disjoint set of ids; the union should be
	// at most 5 (the total number of pending rows).
	const workers = 10
	results := make(chan []*UploadItem, workers)
	for i := 0; i < workers; i++ {
		go func() {
			items, err := q.ClaimPendingUploads(3, 0, 5)
			if err != nil {
				t.Errorf("ClaimPendingUploads: %v", err)
				results <- nil
				return
			}
			results <- items
		}()
	}

	seen := map[int64]int{}
	for i := 0; i < workers; i++ {
		batch := <-results
		for _, item := range batch {
			seen[item.ID]++
		}
	}

	// Each claimed id must appear in exactly one batch.
	if len(seen) != 5 {
		t.Fatalf("expected 5 distinct claimed ids, got %d (counts: %v)", len(seen), seen)
	}
	for id, count := range seen {
		if count != 1 {
			t.Fatalf("id %d claimed %d times, want 1", id, count)
		}
	}

	// After all claims, no PENDING rows remain; the rows are now
	// IN_PROGRESS (claimed atomically and held until the engine marks
	// them complete or failed).
	count, err := q.CountPendingUploads()
	if err != nil {
		t.Fatalf("CountPendingUploads: %v", err)
	}
	if count != 5 {
		t.Fatalf("expected 5 IN_PROGRESS rows after claim, got %d", count)
	}
}

// TestEnqueueEvent_PayloadTooLarge verifies the 64KB cap from T2-8.
func TestEnqueueEvent_PayloadTooLarge(t *testing.T) {
	q := newTestQueue(t)

	// Just under the limit is accepted.
	ok := make([]byte, MaxEventPayloadBytes-1)
	for i := range ok {
		ok[i] = 'a'
	}
	if _, err := q.EnqueueEvent("TEST_OK", string(ok)); err != nil {
		t.Fatalf("just-under-limit payload rejected: %v", err)
	}

	// At the limit is rejected.
	over := make([]byte, MaxEventPayloadBytes+1)
	for i := range over {
		over[i] = 'b'
	}
	_, err := q.EnqueueEvent("TEST_OVER", string(over))
	if err == nil {
		t.Fatalf("over-limit payload accepted")
	}
	if !errors.Is(err, ErrPayloadTooLarge) {
		t.Fatalf("over-limit err = %v, want ErrPayloadTooLarge", err)
	}

	// Confirm the over-limit event was NOT inserted.
	count, err := q.CountPendingEvents()
	if err != nil {
		t.Fatalf("CountPendingEvents: %v", err)
	}
	if count != 1 {
		t.Fatalf("expected 1 pending event (the OK one), got %d", count)
	}
}
