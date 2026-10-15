package engine

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/zeebo/blake3"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

// uploadServer is a mock agent server for upload tests. check-content
// always answers "not found"; /upload runs onUpload, or answers 201.
type uploadServer struct {
	mu       sync.Mutex
	hashes   []string // X-Blake3-Hash of every upload received
	bodies   [][]byte
	onUpload func(w http.ResponseWriter, r *http.Request) bool // true = handled
	uploads  atomic.Int64
}

func (s *uploadServer) handler(w http.ResponseWriter, r *http.Request) {
	switch r.URL.Path {
	case "/api/v1/agent/check-content":
		_ = json.NewEncoder(w).Encode(client.ContentCheckResult{Found: false})
	case "/api/v1/agent/upload":
		n := s.uploads.Add(1)
		if s.onUpload != nil && s.onUpload(w, r) {
			return
		}
		body, _ := io.ReadAll(r.Body)
		s.mu.Lock()
		s.hashes = append(s.hashes, r.Header.Get("X-Blake3-Hash"))
		s.bodies = append(s.bodies, body)
		s.mu.Unlock()
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(client.UploadResponse{OK: true, NodeUUID: fmt.Sprintf("node-%d", n)})
	default:
		http.NotFound(w, r)
	}
}

func newUploadTestEngine(t *testing.T, srv *uploadServer) (*Engine, *queue.Queue, string) {
	t.Helper()
	dir := t.TempDir()
	q, err := queue.Open(filepath.Join(dir, "q.db"))
	if err != nil {
		t.Fatalf("queue.Open: %v", err)
	}
	t.Cleanup(func() { _ = q.Close() })
	ts := httptest.NewServer(http.HandlerFunc(srv.handler))
	t.Cleanup(ts.Close)
	c := client.New(client.Config{BaseURL: ts.URL, APIKey: "k", AgentID: "pixel"})
	return New(q, c), q, dir
}

func enqueueFile(t *testing.T, eng *Engine, dir, name string, data []byte) *queue.UploadItem {
	t.Helper()
	p := filepath.Join(dir, name)
	if err := os.WriteFile(p, data, 0o644); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	item, err := eng.EnqueueLocalCapture(p, name, 1724000000, "local:"+name, "", "")
	if err != nil {
		t.Fatalf("EnqueueLocalCapture: %v", err)
	}
	return item
}

func uploadRow(t *testing.T, q *queue.Queue, id int64) *queue.UploadItem {
	t.Helper()
	item, err := q.GetUploadItem(id)
	if err != nil {
		t.Fatalf("GetUploadItem(%d): %v", id, err)
	}
	return item
}

// assertNoInProgress fails if any row of either queue is IN_PROGRESS.
func assertNoInProgress(t *testing.T, q *queue.Queue) {
	t.Helper()
	for _, table := range []string{"upload_queue", "event_queue"} {
		var n int
		if err := q.DB().QueryRow(`SELECT COUNT(*) FROM ` + table + ` WHERE status = 'IN_PROGRESS'`).Scan(&n); err != nil {
			t.Fatalf("count %s: %v", table, err)
		}
		if n != 0 {
			t.Errorf("%s has %d IN_PROGRESS rows after the sync returned", table, n)
		}
	}
}

func assertUploadsPendingUntouched(t *testing.T, q *queue.Queue, items ...*queue.UploadItem) {
	t.Helper()
	for _, it := range items {
		row := uploadRow(t, q, it.ID)
		if row.Status != queue.UploadPending || row.RetryCount != 0 {
			t.Errorf("item %d: %s/%d, want PENDING/0", it.ID, row.Status, row.RetryCount)
		}
	}
}

// blockUntilCancelled makes /upload hang until the request is abandoned by
// the client, or 3s pass (so a client that never aborts doesn't hang the
// test forever), then answer 201.
func blockUntilCancelled(started chan<- struct{}) func(http.ResponseWriter, *http.Request) bool {
	return func(w http.ResponseWriter, r *http.Request) bool {
		// Drain the (small) body first: the server only notices the
		// client hanging up once the body has been consumed.
		_, _ = io.Copy(io.Discard, r.Body)
		select {
		case started <- struct{}{}:
		default:
		}
		select {
		case <-r.Context().Done():
			return true
		case <-time.After(3 * time.Second):
		}
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(client.UploadResponse{OK: true, NodeUUID: "late-node"})
		return true
	}
}

// TestSyncUploads_CancelFlagMidUploadReleasesBatch: SetCancelFlag while an
// upload streams aborts it, and every claimed row -- the interrupted one
// and the ones never reached -- is PENDING again with no retry used.
func TestSyncUploads_CancelFlagMidUploadReleasesBatch(t *testing.T) {
	started := make(chan struct{}, 1)
	srv := &uploadServer{onUpload: blockUntilCancelled(started)}
	eng, q, dir := newUploadTestEngine(t, srv)
	items := []*queue.UploadItem{
		enqueueFile(t, eng, dir, "a.dng", []byte("aaaa")),
		enqueueFile(t, eng, dir, "b.dng", []byte("bbbb")),
		enqueueFile(t, eng, dir, "c.dng", []byte("cccc")),
	}

	go func() {
		<-started
		eng.RequestCancel()
	}()
	begin := time.Now()
	n, _ := eng.SyncUploads(context.Background(), 10)
	if n != 0 {
		t.Fatalf("completed = %d, want 0", n)
	}
	if d := time.Since(begin); d > 2*time.Second {
		t.Errorf("SyncUploads took %v: the in-flight upload was not aborted", d)
	}
	assertNoInProgress(t, q)
	assertUploadsPendingUntouched(t, q, items...)
	if got := srv.uploads.Load(); got != 1 {
		t.Errorf("server saw %d uploads, want 1", got)
	}
}

// TestSyncUploads_ContextCancelDoesNotBurnRetry: a caller cancelling the
// context mid-upload leaves the item PENDING with its retry count intact.
func TestSyncUploads_ContextCancelDoesNotBurnRetry(t *testing.T) {
	started := make(chan struct{}, 1)
	srv := &uploadServer{onUpload: blockUntilCancelled(started)}
	eng, q, dir := newUploadTestEngine(t, srv)
	a := enqueueFile(t, eng, dir, "a.dng", []byte("aaaa"))
	b := enqueueFile(t, eng, dir, "b.dng", []byte("bbbb"))

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		<-started
		cancel()
	}()
	_, err := eng.SyncUploads(ctx, 10)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("SyncUploads error = %v, want context.Canceled", err)
	}
	assertNoInProgress(t, q)
	assertUploadsPendingUntouched(t, q, a, b)
}

// TestSyncUploads_SlowUploadOutlivesBatchDeadline: an upload still
// streaming when the batch deadline passes finishes under its own budget;
// the batch only stops starting new uploads, and the item it didn't reach
// goes back untouched.
func TestSyncUploads_SlowUploadOutlivesBatchDeadline(t *testing.T) {
	srv := &uploadServer{}
	srv.onUpload = func(w http.ResponseWriter, r *http.Request) bool {
		// Throttled body read: ~1s for the whole body, well past the
		// 250ms batch deadline.
		buf := make([]byte, 4096)
		for {
			time.Sleep(60 * time.Millisecond)
			if _, err := r.Body.Read(buf); err != nil {
				break
			}
		}
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(client.UploadResponse{OK: true, NodeUUID: "slow-node"})
		return true
	}
	eng, q, dir := newUploadTestEngine(t, srv)
	slow := enqueueFile(t, eng, dir, "slow.mp4", make([]byte, 64*1024))
	next := enqueueFile(t, eng, dir, "next.dng", []byte("next"))

	ctx, cancel := context.WithTimeout(context.Background(), 250*time.Millisecond)
	defer cancel()
	n, err := eng.SyncUploads(ctx, 10)
	if n != 1 {
		t.Fatalf("completed = %d (err %v), want the slow upload to complete", n, err)
	}
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Errorf("SyncUploads error = %v, want DeadlineExceeded (batch stopped before the next item)", err)
	}
	if row := uploadRow(t, q, slow.ID); row.Status != queue.UploadCompleted || row.NodeUUID != "slow-node" {
		t.Fatalf("slow upload row = %s/%q, want COMPLETED/slow-node", row.Status, row.NodeUUID)
	}
	assertNoInProgress(t, q)
	assertUploadsPendingUntouched(t, q, next)
	if got := srv.uploads.Load(); got != 1 {
		t.Errorf("server saw %d uploads, want 1 (no upload started after the deadline)", got)
	}
}

// TestSyncUploads_OwnBudgetExpiryCountsAttempt: an upload slower than its
// own budget is a failed attempt (otherwise a stalled file would be retried
// forever without ever counting).
func TestSyncUploads_OwnBudgetExpiryCountsAttempt(t *testing.T) {
	srv := &uploadServer{onUpload: blockUntilCancelled(make(chan struct{}, 1))}
	eng, q, dir := newUploadTestEngine(t, srv)
	eng.uploadBudget = func(int64) time.Duration { return 100 * time.Millisecond }
	item := enqueueFile(t, eng, dir, "stall.dng", []byte("stall"))

	if n, err := eng.SyncUploads(context.Background(), 10); n != 0 || err != nil {
		t.Fatalf("SyncUploads = %d, %v", n, err)
	}
	if row := uploadRow(t, q, item.ID); row.Status != queue.UploadPending || row.RetryCount != 1 {
		t.Fatalf("row = %s/%d, want PENDING/1", row.Status, row.RetryCount)
	}
}

func TestUploadBudget(t *testing.T) {
	if got := UploadBudget(0); got != UploadBaseBudget+time.Second {
		t.Errorf("UploadBudget(0) = %v", got)
	}
	// 4 GiB at the 128 KiB/s floor: 32768s on top of the base.
	if got, want := UploadBudget(4<<30), UploadBaseBudget+32769*time.Second; got != want {
		t.Errorf("UploadBudget(4GiB) = %v, want %v", got, want)
	}
}

// TestSyncUploads_ReclaimsStaleInProgress: a row orphaned IN_PROGRESS
// (claimed over StaleClaimAge ago) is picked up by the next sync without a
// process restart; a fresh claim -- possibly another process's -- is not.
func TestSyncUploads_ReclaimsStaleInProgress(t *testing.T) {
	srv := &uploadServer{}
	eng, q, dir := newUploadTestEngine(t, srv)
	stale := enqueueFile(t, eng, dir, "stale.dng", []byte("stale"))
	if c, err := q.ClaimPendingUploads(10, 0, 5); err != nil || len(c) != 1 {
		t.Fatalf("claim: %v, %v", c, err)
	}
	old := time.Now().Add(-StaleClaimAge - time.Minute).Unix()
	if _, err := q.DB().Exec(`UPDATE upload_queue SET last_attempt_unix = ? WHERE id = ?`, old, stale.ID); err != nil {
		t.Fatal(err)
	}
	fresh := enqueueFile(t, eng, dir, "fresh.dng", []byte("fresh"))
	if c, err := q.ClaimPendingUploads(10, 0, 5); err != nil || len(c) != 1 || c[0].ID != fresh.ID {
		t.Fatalf("claim fresh: %v, %v", c, err)
	}

	n, err := eng.SyncUploads(context.Background(), 10)
	if err != nil || n != 1 {
		t.Fatalf("SyncUploads = %d, %v; want the stale row uploaded", n, err)
	}
	if row := uploadRow(t, q, stale.ID); row.Status != queue.UploadCompleted || row.RetryCount != 0 {
		t.Errorf("stale row = %s/%d, want COMPLETED/0", row.Status, row.RetryCount)
	}
	if row := uploadRow(t, q, fresh.ID); row.Status != queue.UploadInProgress {
		t.Errorf("fresh claim = %s, want left IN_PROGRESS", row.Status)
	}
}

// TestSyncUploads_PolicyBackoffAndMaxRetries: the policy's base backoff
// grows exponentially with each failed attempt and MaxRetries decides when
// the row is FAILED.
func TestSyncUploads_PolicyBackoffAndMaxRetries(t *testing.T) {
	srv := &uploadServer{onUpload: func(w http.ResponseWriter, r *http.Request) bool {
		http.Error(w, "boom", http.StatusInternalServerError)
		return true
	}}
	eng, q, dir := newUploadTestEngine(t, srv)
	item := enqueueFile(t, eng, dir, "x.dng", []byte("x"))
	policy := SyncPolicy{RetryBackoffSecs: 100, MaxRetries: 3}

	sync := func() {
		t.Helper()
		if _, err := eng.SyncUploadsWithPolicy(context.Background(), 10, policy); err != nil {
			t.Fatalf("SyncUploadsWithPolicy: %v", err)
		}
	}
	setAge := func(secs int64) {
		t.Helper()
		if _, err := q.DB().Exec(`UPDATE upload_queue SET last_attempt_unix = ? WHERE id = ?`, time.Now().Unix()-secs, item.ID); err != nil {
			t.Fatal(err)
		}
	}
	expect := func(status queue.UploadStatus, retries int, uploads int64) {
		t.Helper()
		row := uploadRow(t, q, item.ID)
		if row.Status != status || row.RetryCount != retries || srv.uploads.Load() != uploads {
			t.Fatalf("row %s/%d after %d uploads; want %s/%d after %d",
				row.Status, row.RetryCount, srv.uploads.Load(), status, retries, uploads)
		}
	}

	sync()
	expect(queue.UploadPending, 1, 1)
	// retry 1: waits 100<<1 = 200s.
	setAge(150)
	sync()
	expect(queue.UploadPending, 1, 1)
	setAge(201)
	sync()
	expect(queue.UploadPending, 2, 2)
	// retry 2: waits 400s.
	setAge(399)
	sync()
	expect(queue.UploadPending, 2, 2)
	setAge(401)
	sync()
	// Third failure reaches MaxRetries=3.
	expect(queue.UploadFailed, 3, 3)
	setAge(100000)
	sync()
	expect(queue.UploadFailed, 3, 3)
}

// TestSyncEvents_PolicyMaxRetries: SyncEventsWithPolicy fails an event
// after MaxRetries attempts, not the old hard-coded 5.
func TestSyncEvents_PolicyMaxRetries(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	srv.status = http.StatusServiceUnavailable
	recordMedia(t, q, "item", parentNodeUUID)
	id := enqueueDelete(t, q, "item")
	policy := SyncPolicy{RetryBackoffSecs: 1, MaxRetries: 2}
	for i := 0; i < 2; i++ {
		makeClaimable(t, q, id)
		if _, err := eng.SyncEventsWithPolicy(context.Background(), 10, policy); err != nil {
			t.Fatalf("SyncEventsWithPolicy: %v", err)
		}
	}
	if ev := eventRow(t, q, id); ev.Status != queue.EventFailed || ev.RetryCount != 2 {
		t.Fatalf("event = %s/%d, want FAILED/2", ev.Status, ev.RetryCount)
	}
}

func blake3Hex(data []byte) string {
	sum := blake3.Sum256(data)
	return hex.EncodeToString(sum[:])
}

// TestSyncUploads_FileChangedAfterEnqueue: a file rewritten between
// enqueue and upload is re-hashed and sent with hashes of its current
// bytes, never with the stale enqueue-time hash; the local media row
// follows the new hash so it still learns its node UUID.
func TestSyncUploads_FileChangedAfterEnqueue(t *testing.T) {
	cases := []struct {
		name   string
		after  []byte
		bumpMt bool
	}{
		{"size changed", []byte("edited and longer bytes"), false},
		{"same size, new mtime", []byte("EDITED!!"), true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			// Like the server, refuse a body whose BLAKE3 doesn't match
			// the hash header.
			srv := &uploadServer{}
			srv.onUpload = func(w http.ResponseWriter, r *http.Request) bool {
				body, _ := io.ReadAll(r.Body)
				hash := r.Header.Get("X-Blake3-Hash")
				srv.mu.Lock()
				srv.hashes = append(srv.hashes, hash)
				srv.bodies = append(srv.bodies, body)
				srv.mu.Unlock()
				if hash != blake3Hex(body) {
					http.Error(w, "hash mismatch", http.StatusUnprocessableEntity)
					return true
				}
				w.WriteHeader(http.StatusCreated)
				_ = json.NewEncoder(w).Encode(client.UploadResponse{OK: true, NodeUUID: "edited-node"})
				return true
			}
			eng, q, dir := newUploadTestEngine(t, srv)
			original := []byte("original")
			item := enqueueFile(t, eng, dir, "edit.jpg", original)
			path := filepath.Join(dir, "edit.jpg")
			if err := os.WriteFile(path, c.after, 0o644); err != nil {
				t.Fatal(err)
			}
			if c.bumpMt {
				later := time.Now().Add(time.Hour)
				if err := os.Chtimes(path, later, later); err != nil {
					t.Fatal(err)
				}
			}

			n, err := eng.SyncUploads(context.Background(), 10)
			if err != nil || n != 1 {
				t.Fatalf("SyncUploads = %d, %v; want 1 upload", n, err)
			}
			want := blake3Hex(c.after)
			if len(srv.hashes) != 1 || srv.hashes[0] != want || string(srv.bodies[0]) != string(c.after) {
				t.Fatalf("server got hashes %v, want exactly [%s] for the current bytes", srv.hashes, want)
			}
			row := uploadRow(t, q, item.ID)
			if row.Status != queue.UploadCompleted || row.Blake3Hash != want || row.SizeBytes != int64(len(c.after)) {
				t.Fatalf("row = %s %s size %d", row.Status, row.Blake3Hash, row.SizeBytes)
			}
			media, err := q.GetMediaByLocalID("local:edit.jpg")
			if err != nil || media.Blake3Hash != want || media.NodeUUID == "" {
				t.Fatalf("local media = %+v, %v; want new hash and a node UUID", media, err)
			}
		})
	}
}

// TestSyncUploads_UnchangedFileNotRehashed: the re-stat leaves an
// untouched file alone.
func TestSyncUploads_UnchangedFileNotRehashed(t *testing.T) {
	srv := &uploadServer{}
	eng, q, dir := newUploadTestEngine(t, srv)
	item := enqueueFile(t, eng, dir, "same.jpg", []byte("same"))
	if item.ModTimeUnixNano == 0 {
		t.Fatal("enqueue did not record the file's mtime")
	}
	if n, err := eng.SyncUploads(context.Background(), 10); err != nil || n != 1 {
		t.Fatalf("SyncUploads = %d, %v", n, err)
	}
	if srv.hashes[0] != item.Blake3Hash {
		t.Fatalf("sent %s, want %s", srv.hashes[0], item.Blake3Hash)
	}
	if row := uploadRow(t, q, item.ID); row.UpdatedAtUnix == 0 || row.Blake3Hash != item.Blake3Hash {
		t.Fatalf("row = %+v", row)
	}
}

// TestSyncUploads_AuthFailureStopsBatch: a 401 from the server ends the
// batch with AUTH_FAILED and every claimed row PENDING with no retry used.
func TestSyncUploads_AuthFailureStopsBatch(t *testing.T) {
	for _, status := range []int{http.StatusUnauthorized, http.StatusForbidden} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			srv := &uploadServer{onUpload: func(w http.ResponseWriter, r *http.Request) bool {
				http.Error(w, `{"error":"invalid api key"}`, status)
				return true
			}}
			eng, q, dir := newUploadTestEngine(t, srv)
			a := enqueueFile(t, eng, dir, "a.dng", []byte("a"))
			b := enqueueFile(t, eng, dir, "b.dng", []byte("b"))

			n, err := eng.SyncUploads(context.Background(), 10)
			if n != 0 || !client.IsAuthError(err) {
				t.Fatalf("SyncUploads = %d, %v; want 0 and AUTH_FAILED", n, err)
			}
			var ce *client.ClientError
			if !errors.As(err, &ce) || ce.StatusCode != status {
				t.Fatalf("error %v does not carry status %d", err, status)
			}
			assertNoInProgress(t, q)
			assertUploadsPendingUntouched(t, q, a, b)
			if got := srv.uploads.Load(); got != 1 {
				t.Errorf("server saw %d uploads, want 1 (batch stops at the first 401)", got)
			}
		})
	}
}

// TestSyncEvents_CancelMidBatchReleasesClaims: SetCancelFlag during an
// event batch hands back every event not yet sent.
func TestSyncEvents_CancelMidBatchReleasesClaims(t *testing.T) {
	eng, q, _ := newEventTestEngine(t)
	ids := make([]int64, 3)
	for i := range ids {
		recordMedia(t, q, fmt.Sprintf("item-%d", i), parentNodeUUID)
		ids[i] = enqueueDelete(t, q, fmt.Sprintf("item-%d", i))
	}
	// The cancel lands after the first event has been handed to the
	// server; the eventServer has no hook, so cancel from a wrapper.
	var once sync.Once
	wrapped := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		once.Do(eng.RequestCancel)
		w.WriteHeader(http.StatusAccepted)
		_, _ = io.WriteString(w, `{}`)
	}))
	t.Cleanup(wrapped.Close)
	eng.c = client.New(client.Config{BaseURL: wrapped.URL, APIKey: "k", AgentID: "pixel"})

	n, err := eng.SyncEvents(context.Background(), 10)
	if err != nil || n != 1 {
		t.Fatalf("SyncEvents = %d, %v; want 1 sent before the cancel", n, err)
	}
	assertNoInProgress(t, q)
	for _, id := range ids[1:] {
		if ev := eventRow(t, q, id); ev.Status != queue.EventPending || ev.RetryCount != 0 {
			t.Errorf("event %d = %s/%d, want PENDING/0", id, ev.Status, ev.RetryCount)
		}
	}
}

// TestSyncEvents_DeadlineMidRequestDoesNotBurnRetry: the batch deadline
// expiring while an event is being sent hands it back without a retry.
func TestSyncEvents_DeadlineMidRequestDoesNotBurnRetry(t *testing.T) {
	eng, q, _ := newEventTestEngine(t)
	ids := make([]int64, 2)
	for i := range ids {
		recordMedia(t, q, fmt.Sprintf("item-%d", i), parentNodeUUID)
		ids[i] = enqueueDelete(t, q, fmt.Sprintf("item-%d", i))
	}
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		select {
		case <-r.Context().Done():
		case <-time.After(2 * time.Second):
		}
		w.WriteHeader(http.StatusAccepted)
	}))
	t.Cleanup(slow.Close)
	eng.c = client.New(client.Config{BaseURL: slow.URL, APIKey: "k", AgentID: "pixel"})

	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	if _, err := eng.SyncEvents(ctx, 10); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("SyncEvents error = %v, want DeadlineExceeded", err)
	}
	assertNoInProgress(t, q)
	for _, id := range ids {
		if ev := eventRow(t, q, id); ev.Status != queue.EventPending || ev.RetryCount != 0 {
			t.Errorf("event %d = %s/%d, want PENDING/0", id, ev.Status, ev.RetryCount)
		}
	}
}
