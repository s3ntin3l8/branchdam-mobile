package queue

import (
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
	"time"
)

func setLastAttempt(t *testing.T, q *Queue, table string, id, lastAttempt int64) {
	t.Helper()
	if _, err := q.DB().Exec(`UPDATE `+table+` SET last_attempt_unix = ? WHERE id = ?`, lastAttempt, id); err != nil {
		t.Fatalf("set last_attempt_unix: %v", err)
	}
}

func setRetryCount(t *testing.T, q *Queue, table string, id int64, n int) {
	t.Helper()
	if _, err := q.DB().Exec(`UPDATE `+table+` SET retry_count = ? WHERE id = ?`, n, id); err != nil {
		t.Fatalf("set retry_count: %v", err)
	}
}

func rowState(t *testing.T, q *Queue, table string, id int64) (status string, retries int) {
	t.Helper()
	if err := q.DB().QueryRow(`SELECT status, retry_count FROM `+table+` WHERE id = ?`, id).Scan(&status, &retries); err != nil {
		t.Fatalf("read %s row %d: %v", table, id, err)
	}
	return status, retries
}

func enqueueTestUpload(t *testing.T, q *Queue, name string) int64 {
	t.Helper()
	id, err := q.EnqueueUpload(&UploadItem{LocalPath: "/sdcard/" + name, TargetFilename: name, Blake3Hash: "b3-" + name})
	if err != nil {
		t.Fatalf("EnqueueUpload: %v", err)
	}
	return id
}

func enqueueTestEvent(t *testing.T, q *Queue) int64 {
	t.Helper()
	eventUUID, err := q.EnqueueEvent("EVENT_NODE_DELETED", `{"localId":"x"}`)
	if err != nil {
		t.Fatalf("EnqueueEvent: %v", err)
	}
	var id int64
	if err := q.DB().QueryRow(`SELECT id FROM event_queue WHERE event_uuid = ?`, eventUUID).Scan(&id); err != nil {
		t.Fatalf("lookup event id: %v", err)
	}
	return id
}

// TestOpen_ResetsEventQueueInProgress: an event claimed by a session that
// died is handed back on the next Open, like an upload, without a retry.
func TestOpen_ResetsEventQueueInProgress(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "q.db")
	q1, err := Open(dbPath)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	id := enqueueTestEvent(t, q1)
	if claimed, err := q1.ClaimPendingEvents(10, 0, 5); err != nil || len(claimed) != 1 {
		t.Fatalf("ClaimPendingEvents = %d, %v", len(claimed), err)
	}
	_ = q1.Close()

	q2, err := Open(dbPath)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	t.Cleanup(func() { _ = q2.Close() })
	if status, retries := rowState(t, q2, "event_queue", id); status != "PENDING" || retries != 0 {
		t.Fatalf("event after reopen: %s/%d, want PENDING/0", status, retries)
	}
	if claimed, err := q2.ClaimPendingEvents(10, 10, 5); err != nil || len(claimed) != 1 {
		t.Fatalf("recovered event not claimable at once: %d, %v", len(claimed), err)
	}
}

// TestReclaimStaleClaims: IN_PROGRESS rows older than the threshold, in
// both queues, go back to PENDING without a retry; fresh claims stay put.
func TestReclaimStaleClaims(t *testing.T) {
	q := newTestQueue(t)
	staleUp := enqueueTestUpload(t, q, "stale.dng")
	freshUp := enqueueTestUpload(t, q, "fresh.dng")
	staleEv := enqueueTestEvent(t, q)
	freshEv := enqueueTestEvent(t, q)
	if c, err := q.ClaimPendingUploads(10, 0, 5); err != nil || len(c) != 2 {
		t.Fatalf("claim uploads: %d, %v", len(c), err)
	}
	if c, err := q.ClaimPendingEvents(10, 0, 5); err != nil || len(c) != 2 {
		t.Fatalf("claim events: %d, %v", len(c), err)
	}
	setRetryCount(t, q, "upload_queue", staleUp, 2)
	old := time.Now().Add(-31 * time.Minute).Unix()
	setLastAttempt(t, q, "upload_queue", staleUp, old)
	setLastAttempt(t, q, "event_queue", staleEv, old)

	ups, evs, err := q.ReclaimStaleClaims(30 * time.Minute)
	if err != nil || ups != 1 || evs != 1 {
		t.Fatalf("ReclaimStaleClaims = %d, %d, %v; want 1, 1, nil", ups, evs, err)
	}
	if s, r := rowState(t, q, "upload_queue", staleUp); s != "PENDING" || r != 2 {
		t.Errorf("stale upload: %s/%d, want PENDING/2 (retry count untouched)", s, r)
	}
	if s, _ := rowState(t, q, "upload_queue", freshUp); s != "IN_PROGRESS" {
		t.Errorf("fresh upload: %s, want IN_PROGRESS", s)
	}
	if s, r := rowState(t, q, "event_queue", staleEv); s != "PENDING" || r != 0 {
		t.Errorf("stale event: %s/%d, want PENDING/0", s, r)
	}
	if s, _ := rowState(t, q, "event_queue", freshEv); s != "IN_PROGRESS" {
		t.Errorf("fresh event: %s, want IN_PROGRESS", s)
	}
	if _, _, err := q.ReclaimStaleClaims(0); err == nil {
		t.Errorf("ReclaimStaleClaims(0) should be rejected")
	}
}

// TestReleaseClaimed: released rows are PENDING, keep their retry count
// and are claimable at once; a row already finished is not touched.
func TestReleaseClaimed(t *testing.T) {
	q := newTestQueue(t)
	a := enqueueTestUpload(t, q, "a.dng")
	b := enqueueTestUpload(t, q, "b.dng")
	setRetryCount(t, q, "upload_queue", b, 1)
	if c, err := q.ClaimPendingUploads(10, 0, 5); err != nil || len(c) != 2 {
		t.Fatalf("claim: %d, %v", len(c), err)
	}
	if err := q.MarkUploadComplete(a, "node-a"); err != nil {
		t.Fatalf("MarkUploadComplete: %v", err)
	}
	if err := q.ReleaseUploads([]int64{a, b}); err != nil {
		t.Fatalf("ReleaseUploads: %v", err)
	}
	if s, _ := rowState(t, q, "upload_queue", a); s != "COMPLETED" {
		t.Errorf("completed row became %s", s)
	}
	if s, r := rowState(t, q, "upload_queue", b); s != "PENDING" || r != 1 {
		t.Errorf("released row: %s/%d, want PENDING/1", s, r)
	}
	// Claimable even with a long backoff: the interrupted attempt does not
	// restart the wait.
	if c, err := q.ClaimPendingUploads(10, 3600, 5); err != nil || len(c) != 1 || c[0].ID != b {
		t.Fatalf("released row not claimable: %v, %v", c, err)
	}

	ev := enqueueTestEvent(t, q)
	if c, err := q.ClaimPendingEvents(10, 0, 5); err != nil || len(c) != 1 {
		t.Fatalf("claim events: %d, %v", len(c), err)
	}
	if err := q.ReleaseEvents([]int64{ev}); err != nil {
		t.Fatalf("ReleaseEvents: %v", err)
	}
	if s, r := rowState(t, q, "event_queue", ev); s != "PENDING" || r != 0 {
		t.Errorf("released event: %s/%d, want PENDING/0", s, r)
	}
	if err := q.ReleaseEvents(nil); err != nil {
		t.Errorf("ReleaseEvents(nil): %v", err)
	}
}

// TestClaim_ExponentialBackoff: after a failed attempt a row waits
// base << retry_count seconds, capped at an hour, in both queues.
func TestClaim_ExponentialBackoff(t *testing.T) {
	const base = 10
	cases := []struct {
		retries int
		wait    int64 // seconds the row must wait
	}{
		{0, 10}, // deferred, never failed: the plain base
		{1, 20},
		{3, 80},
		{8, 2560},
		{9, DefaultMaxRetryBackoffSecs}, // 5120 capped
		{40, DefaultMaxRetryBackoffSecs},
	}
	for _, c := range cases {
		q := newTestQueue(t)
		up := enqueueTestUpload(t, q, "b.dng")
		ev := enqueueTestEvent(t, q)
		setRetryCount(t, q, "upload_queue", up, c.retries)
		setRetryCount(t, q, "event_queue", ev, c.retries)

		now := time.Now().Unix()
		setLastAttempt(t, q, "upload_queue", up, now-c.wait+2)
		setLastAttempt(t, q, "event_queue", ev, now-c.wait+2)
		if got, _ := q.ClaimPendingUploads(10, base, 100); len(got) != 0 {
			t.Errorf("retries=%d: upload claimed %ds early", c.retries, 2)
		}
		if got, _ := q.ClaimPendingEvents(10, base, 100); len(got) != 0 {
			t.Errorf("retries=%d: event claimed %ds early", c.retries, 2)
		}

		setLastAttempt(t, q, "upload_queue", up, now-c.wait-1)
		setLastAttempt(t, q, "event_queue", ev, now-c.wait-1)
		if got, _ := q.ClaimPendingUploads(10, base, 100); len(got) != 1 {
			t.Errorf("retries=%d: upload not claimable after its %ds backoff", c.retries, c.wait)
		}
		if got, _ := q.ClaimPendingEvents(10, base, 100); len(got) != 1 {
			t.Errorf("retries=%d: event not claimable after its %ds backoff", c.retries, c.wait)
		}
	}
}

// TestOpen_MigratesUploadQueueWithoutMtime: a database from before the
// mtime column (and the pending_import table) opens, gains both, and its
// rows read back with a zero mtime.
func TestOpen_MigratesUploadQueueWithoutMtime(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "old.db")
	old, err := sql.Open("sqlite", "file:"+dbPath)
	if err != nil {
		t.Fatalf("sql.Open: %v", err)
	}
	if _, err := old.Exec(`
	CREATE TABLE upload_queue (
	    id INTEGER PRIMARY KEY AUTOINCREMENT,
	    local_path TEXT NOT NULL, target_filename TEXT NOT NULL,
	    target_dir TEXT NOT NULL DEFAULT '', fast_hash TEXT NOT NULL DEFAULT '',
	    blake3_hash TEXT NOT NULL DEFAULT '', camera_model TEXT NOT NULL DEFAULT '',
	    size_bytes INTEGER NOT NULL DEFAULT 0, captured_at_unix INTEGER NOT NULL DEFAULT 0,
	    status TEXT NOT NULL DEFAULT 'PENDING', retry_count INTEGER NOT NULL DEFAULT 0,
	    last_attempt_unix INTEGER NOT NULL DEFAULT 0, error_msg TEXT NOT NULL DEFAULT '',
	    node_uuid TEXT NOT NULL DEFAULT '', created_at_unix INTEGER NOT NULL,
	    updated_at_unix INTEGER NOT NULL);
	INSERT INTO upload_queue (local_path, target_filename, blake3_hash, size_bytes, status, created_at_unix, updated_at_unix)
	VALUES ('/sdcard/old.dng', 'old.dng', 'b3-old', 42, 'IN_PROGRESS', 1, 1);`); err != nil {
		t.Fatalf("create old schema: %v", err)
	}
	_ = old.Close()

	q, err := Open(dbPath)
	if err != nil {
		t.Fatalf("Open old db: %v", err)
	}
	t.Cleanup(func() { _ = q.Close() })
	item, err := q.GetUploadItem(1)
	if err != nil {
		t.Fatalf("GetUploadItem: %v", err)
	}
	if item.ModTimeUnixNano != 0 || item.SizeBytes != 42 || item.Status != UploadPending {
		t.Fatalf("migrated row = %+v", item)
	}
	if _, err := q.StagePendingImport(PendingImport{LocalID: "content://1"}); err != nil {
		t.Fatalf("pending_import missing after migration: %v", err)
	}
}

// TestUpdateUploadContent: new hashes, size and mtime are recorded, and
// local media rows still waiting on the old hash follow it; a row already
// linked to a node keeps its hash.
func TestUpdateUploadContent(t *testing.T) {
	q := newTestQueue(t)
	id, err := q.EnqueueUpload(&UploadItem{LocalPath: "/p", TargetFilename: "p.jpg", FastHash: "f-old", Blake3Hash: "b3-old", SizeBytes: 1, ModTimeUnixNano: 5})
	if err != nil {
		t.Fatalf("EnqueueUpload: %v", err)
	}
	if err := q.RecordLocalMedia("waiting", "", "b3-old", "ACTIVE"); err != nil {
		t.Fatal(err)
	}
	if err := q.RecordLocalMedia("linked", "node-1", "b3-old", "ACTIVE"); err != nil {
		t.Fatal(err)
	}
	if err := q.UpdateUploadContent(id, "f-new", "b3-new", 2, 9); err != nil {
		t.Fatalf("UpdateUploadContent: %v", err)
	}
	item, err := q.GetUploadItem(id)
	if err != nil {
		t.Fatal(err)
	}
	if item.FastHash != "f-new" || item.Blake3Hash != "b3-new" || item.SizeBytes != 2 || item.ModTimeUnixNano != 9 {
		t.Fatalf("row after update = %+v", item)
	}
	if m, _ := q.GetMediaByLocalID("waiting"); m.Blake3Hash != "b3-new" {
		t.Errorf("waiting media hash = %q, want b3-new", m.Blake3Hash)
	}
	if m, _ := q.GetMediaByLocalID("linked"); m.Blake3Hash != "b3-old" {
		t.Errorf("linked media hash = %q, want b3-old", m.Blake3Hash)
	}
}

// TestPendingImportCRUD covers staging, listing, suppression and deletion.
func TestPendingImportCRUD(t *testing.T) {
	q := newTestQueue(t)

	for _, p := range []PendingImport{
		{LocalID: "content://2", LocalPath: "/dcim/2.jpg", Filename: "2.jpg", MimeType: "image/jpeg", SizeBytes: 20, CapturedAtUnix: 200},
		{LocalID: "content://1", LocalPath: "/dcim/1.jpg", Filename: "1.jpg", MimeType: "image/jpeg", SizeBytes: 10, CapturedAtUnix: 100},
	} {
		if staged, err := q.StagePendingImport(p); err != nil || !staged {
			t.Fatalf("StagePendingImport(%s) = %v, %v", p.LocalID, staged, err)
		}
	}
	// Re-staging refreshes metadata.
	if staged, err := q.StagePendingImport(PendingImport{LocalID: "content://2", LocalPath: "/dcim/2b.jpg", Filename: "2b.jpg", CapturedAtUnix: 200}); err != nil || !staged {
		t.Fatalf("re-stage = %v, %v", staged, err)
	}

	list, err := q.ListPendingImports(0)
	if err != nil || len(list) != 2 {
		t.Fatalf("ListPendingImports = %d, %v", len(list), err)
	}
	if list[0].LocalID != "content://1" || list[1].LocalPath != "/dcim/2b.jpg" || list[0].State != PendingImportPending {
		t.Fatalf("list order/contents = %+v, %+v", *list[0], *list[1])
	}
	if one, _ := q.ListPendingImports(1); len(one) != 1 {
		t.Errorf("limit 1 returned %d", len(one))
	}

	// Skip one: it leaves the list, stays suppressed when re-staged.
	if err := q.SuppressPendingImport("content://1"); err != nil {
		t.Fatalf("SuppressPendingImport: %v", err)
	}
	if staged, err := q.StagePendingImport(PendingImport{LocalID: "content://1", Filename: "again.jpg"}); err != nil || staged {
		t.Fatalf("re-staging a skipped item = %v, %v; want false, nil", staged, err)
	}
	if sup, err := q.IsImportSuppressed("content://1"); err != nil || !sup {
		t.Fatalf("IsImportSuppressed = %v, %v", sup, err)
	}
	if p, err := q.GetPendingImport("content://1"); err != nil || p.Filename != "1.jpg" || p.State != PendingImportSuppressed {
		t.Fatalf("suppressed row = %+v, %v", p, err)
	}
	if list, _ := q.ListPendingImports(0); len(list) != 1 || list[0].LocalID != "content://2" {
		t.Fatalf("list after skip = %v", list)
	}
	// Suppressing an item that was never staged works too.
	if err := q.SuppressPendingImport("content://never-staged"); err != nil {
		t.Fatal(err)
	}
	if sup, _ := q.IsImportSuppressed("content://never-staged"); !sup {
		t.Error("unstaged suppression not recorded")
	}
	if sup, _ := q.IsImportSuppressed("content://2"); sup {
		t.Error("pending item reported as suppressed")
	}

	// Import accepted: delete. Deleting a suppression lifts it.
	if deleted, err := q.DeletePendingImport("content://2"); err != nil || !deleted {
		t.Fatalf("DeletePendingImport = %v, %v", deleted, err)
	}
	if deleted, _ := q.DeletePendingImport("content://2"); deleted {
		t.Error("second delete reported a row")
	}
	if _, err := q.GetPendingImport("content://2"); !errors.Is(err, sql.ErrNoRows) {
		t.Errorf("GetPendingImport after delete: %v", err)
	}
	if _, err := q.DeletePendingImport("content://1"); err != nil {
		t.Fatal(err)
	}
	if sup, _ := q.IsImportSuppressed("content://1"); sup {
		t.Error("suppression survived delete")
	}

	if _, err := q.StagePendingImport(PendingImport{}); !errors.Is(err, ErrLocalIDRequired) {
		t.Errorf("empty localID: %v", err)
	}
	if err := q.SuppressPendingImport(""); !errors.Is(err, ErrLocalIDRequired) {
		t.Errorf("empty localID: %v", err)
	}
	if _, err := q.DeletePendingImport(""); !errors.Is(err, ErrLocalIDRequired) {
		t.Errorf("empty localID: %v", err)
	}
}

// TestPendingImportSurvivesReopen: the point of the table -- intent is
// still there after the process dies.
func TestPendingImportSurvivesReopen(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "q.db")
	q1, err := Open(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := q1.StagePendingImport(PendingImport{LocalID: "ph://A", Filename: "A.heic"}); err != nil {
		t.Fatal(err)
	}
	if err := q1.SuppressPendingImport("ph://B"); err != nil {
		t.Fatal(err)
	}
	_ = q1.Close()

	q2, err := Open(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = q2.Close() })
	if list, _ := q2.ListPendingImports(0); len(list) != 1 || list[0].LocalID != "ph://A" {
		t.Fatalf("pending after reopen = %v", list)
	}
	if sup, _ := q2.IsImportSuppressed("ph://B"); !sup {
		t.Fatal("suppression lost on reopen")
	}
}

// TestOpen_MigratesLocalMediaStateWithoutBaseline: a local_media_state from
// before the reclaim baseline (#229) opens, gains size_bytes and
// modified_unix, and its rows read back with a zero (unknown) baseline.
func TestOpen_MigratesLocalMediaStateWithoutBaseline(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "old-media.db")
	old, err := sql.Open("sqlite", "file:"+dbPath)
	if err != nil {
		t.Fatalf("sql.Open: %v", err)
	}
	if _, err := old.Exec(`
	CREATE TABLE local_media_state (
	    local_id TEXT PRIMARY KEY, node_uuid TEXT NOT NULL DEFAULT '',
	    blake3_hash TEXT NOT NULL DEFAULT '', lifecycle_state TEXT NOT NULL DEFAULT 'ACTIVE',
	    is_offloaded INTEGER NOT NULL DEFAULT 0, created_at_unix INTEGER NOT NULL,
	    updated_at_unix INTEGER NOT NULL);
	INSERT INTO local_media_state (local_id, node_uuid, blake3_hash, created_at_unix, updated_at_unix)
	VALUES ('content://media/1', 'node-1', 'b3-old', 1, 1);`); err != nil {
		t.Fatalf("create old schema: %v", err)
	}
	_ = old.Close()

	q, err := Open(dbPath)
	if err != nil {
		t.Fatalf("Open old db: %v", err)
	}
	t.Cleanup(func() { _ = q.Close() })
	m, err := q.GetMediaByLocalID("content://media/1")
	if err != nil {
		t.Fatalf("GetMediaByLocalID: %v", err)
	}
	if m.Blake3Hash != "b3-old" || m.SizeBytes != 0 || m.ModifiedUnix != 0 {
		t.Fatalf("migrated row = %+v", m)
	}
}

// TestRecordLocalMediaWithBaseline: the baseline round-trips, and every
// re-record overwrites it together with the hash -- a plain RecordLocalMedia
// clears it rather than leaving a baseline that described other bytes.
func TestRecordLocalMediaWithBaseline(t *testing.T) {
	q := newTestQueue(t)
	if err := q.RecordLocalMediaWithBaseline("c://1", "node-1", "b3", "ACTIVE", 4096, 1700000000); err != nil {
		t.Fatal(err)
	}
	m, err := q.GetMediaByLocalID("c://1")
	if err != nil {
		t.Fatal(err)
	}
	if m.SizeBytes != 4096 || m.ModifiedUnix != 1700000000 {
		t.Fatalf("baseline = (%d, %d), want (4096, 1700000000)", m.SizeBytes, m.ModifiedUnix)
	}
	if byNode, err := q.GetMediaByNodeUUID("node-1"); err != nil || byNode.SizeBytes != 4096 || byNode.ModifiedUnix != 1700000000 {
		t.Fatalf("GetMediaByNodeUUID = %+v, %v", byNode, err)
	}
	if err := q.RecordLocalMedia("c://1", "node-1", "b3-new", "ACTIVE"); err != nil {
		t.Fatal(err)
	}
	if m, _ := q.GetMediaByLocalID("c://1"); m.SizeBytes != 0 || m.ModifiedUnix != 0 || m.Blake3Hash != "b3-new" {
		t.Fatalf("after re-record without baseline = %+v", m)
	}
}
