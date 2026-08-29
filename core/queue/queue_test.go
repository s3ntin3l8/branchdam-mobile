package queue

import (
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
		Blake3Hash:     "b3f1c4d9e2a7568013c9a4d2e8f7b1063c5a9d7e2f4b8016938ac1d4e7f2b09a",
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
	blake3 := "b3f1c4d9e2a7568013c9a4d2e8f7b1063c5a9d7e2f4b8016938ac1d4e7f2b09a"

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
