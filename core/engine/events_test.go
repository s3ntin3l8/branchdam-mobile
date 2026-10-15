package engine

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/s3ntin3l8/branchdam-mobile/core/client"
	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

const (
	parentNodeUUID = "0190f1a2-3b4c-7d5e-8f6a-1b2c3d4e5f60"
	childNodeUUID  = "0190f1a2-3b4c-7d5e-8f6a-1b2c3d4e5f61"
)

// eventServer is a mock /api/v1/agent/events endpoint that records every
// request body it receives and answers with status (202 by default).
type eventServer struct {
	mu       sync.Mutex
	requests []client.AgentEventRequest
	status   int
	body     string
}

func (s *eventServer) handler(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/api/v1/agent/events" {
		http.NotFound(w, r)
		return
	}
	raw, _ := io.ReadAll(r.Body)
	var req client.AgentEventRequest
	_ = json.Unmarshal(raw, &req)
	s.mu.Lock()
	s.requests = append(s.requests, req)
	status, body := s.status, s.body
	s.mu.Unlock()
	if status == 0 {
		w.WriteHeader(http.StatusAccepted)
		_ = json.NewEncoder(w).Encode(client.AgentEventResponse{EventID: req.EventUUID})
		return
	}
	w.WriteHeader(status)
	_, _ = io.WriteString(w, body)
}

func (s *eventServer) sent() []client.AgentEventRequest {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]client.AgentEventRequest(nil), s.requests...)
}

func newEventTestEngine(t *testing.T) (*Engine, *queue.Queue, *eventServer) {
	t.Helper()
	q, err := queue.Open(filepath.Join(t.TempDir(), "events.db"))
	if err != nil {
		t.Fatalf("queue.Open: %v", err)
	}
	t.Cleanup(func() { _ = q.Close() })
	srv := &eventServer{}
	ts := httptest.NewServer(http.HandlerFunc(srv.handler))
	t.Cleanup(ts.Close)
	c := client.New(client.Config{BaseURL: ts.URL, APIKey: "test-key", AgentID: "pixel-test"})
	return New(q, c), q, srv
}

func enqueueLineage(t *testing.T, q *queue.Queue, in LineageIntent) int64 {
	t.Helper()
	payload, err := EncodeLineageIntent(in)
	if err != nil {
		t.Fatalf("EncodeLineageIntent: %v", err)
	}
	return enqueueRaw(t, q, EventEdgeAttached, payload)
}

func enqueueDelete(t *testing.T, q *queue.Queue, localID string) int64 {
	t.Helper()
	payload, err := EncodeDeleteIntent(DeleteIntent{LocalID: localID})
	if err != nil {
		t.Fatalf("EncodeDeleteIntent: %v", err)
	}
	return enqueueRaw(t, q, EventNodeDeleted, payload)
}

func enqueueRaw(t *testing.T, q *queue.Queue, eventType, payload string) int64 {
	t.Helper()
	eventUUID, err := q.EnqueueEvent(eventType, payload)
	if err != nil {
		t.Fatalf("EnqueueEvent: %v", err)
	}
	var id int64
	if err := q.DB().QueryRow(`SELECT id FROM event_queue WHERE event_uuid = ?`, eventUUID).Scan(&id); err != nil {
		t.Fatalf("lookup event id: %v", err)
	}
	return id
}

func recordMedia(t *testing.T, q *queue.Queue, localID, nodeUUID string) {
	t.Helper()
	if err := q.RecordLocalMedia(localID, nodeUUID, "", "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia(%q): %v", localID, err)
	}
}

func syncEvents(t *testing.T, eng *Engine, batch int) int {
	t.Helper()
	n, err := eng.SyncEvents(context.Background(), batch)
	if err != nil {
		t.Fatalf("SyncEvents: %v", err)
	}
	return n
}

func eventRow(t *testing.T, q *queue.Queue, id int64) *queue.EventItem {
	t.Helper()
	ev, err := q.GetEvent(id)
	if err != nil {
		t.Fatalf("GetEvent(%d): %v", id, err)
	}
	return ev
}

// makeClaimable clears the retry-backoff stamp so the next SyncEvents can
// claim the event again without waiting out the backoff.
func makeClaimable(t *testing.T, q *queue.Queue, id int64) {
	t.Helper()
	if _, err := q.DB().Exec(`UPDATE event_queue SET last_attempt_unix = 1 WHERE id = ?`, id); err != nil {
		t.Fatalf("reset backoff: %v", err)
	}
}

// TestSyncEvents_EdgeAttachedGoldenPayload pins the exact JSON a mobile
// lineage edge is sent as: the server's EdgeAttachedPayload with the parent
// as source and the child as target, tier 1, no reviewState and no
// server-internal node IDs.
func TestSyncEvents_EdgeAttachedGoldenPayload(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	recordMedia(t, q, "content://media/external/images/media/10", parentNodeUUID)
	recordMedia(t, q, "content://media/external/images/media/11", childNodeUUID)
	id := enqueueLineage(t, q, LineageIntent{
		ParentLocalID: "content://media/external/images/media/10",
		ChildLocalID:  "content://media/external/images/media/11",
		Relationship:  "DERIVED_FROM",
		Confidence:    1.0,
		Resolver:      "android_camera_pair",
	})
	queued := eventRow(t, q, id)

	if n := syncEvents(t, eng, 10); n != 1 {
		t.Fatalf("sent = %d, want 1", n)
	}
	reqs := srv.sent()
	if len(reqs) != 1 {
		t.Fatalf("server saw %d requests, want 1", len(reqs))
	}
	want := `{"sourceNodeUuid":"` + parentNodeUUID + `","targetNodeUuid":"` + childNodeUUID + `","relationshipType":"DERIVED_FROM","confidence":1,"tier":1,"resolver":"android_camera_pair"}`
	if reqs[0].Payload != want {
		t.Errorf("payload =\n  %s\nwant\n  %s", reqs[0].Payload, want)
	}
	if reqs[0].EventType != EventEdgeAttached || reqs[0].EventUUID != queued.EventUUID || reqs[0].AgentID != "pixel-test" {
		t.Errorf("envelope = %+v, want type %s, uuid %s, agent pixel-test", reqs[0], EventEdgeAttached, queued.EventUUID)
	}
	if got := eventRow(t, q, id).Status; got != queue.EventSent {
		t.Errorf("status = %s, want SENT", got)
	}
}

// TestSyncEvents_MotionPhotoAliasGoldenPayload: the Android-only
// MOTION_PHOTO_CONTAINS relationship goes out as the server's DERIVED_FROM,
// keeping the original name in evidenceJson (an object, not a string).
func TestSyncEvents_MotionPhotoAliasGoldenPayload(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	recordMedia(t, q, "content://media/external/images/media/20", parentNodeUUID)
	recordMedia(t, q, "content://media/external/images/media/20/microvideo", childNodeUUID)
	enqueueLineage(t, q, LineageIntent{
		ParentLocalID: "content://media/external/images/media/20",
		ChildLocalID:  "content://media/external/images/media/20/microvideo",
		Relationship:  "MOTION_PHOTO_CONTAINS",
		Confidence:    0.95,
		Resolver:      "android_motion_photo_xmp",
	})

	syncEvents(t, eng, 10)
	reqs := srv.sent()
	if len(reqs) != 1 {
		t.Fatalf("server saw %d requests, want 1", len(reqs))
	}
	want := `{"sourceNodeUuid":"` + parentNodeUUID + `","targetNodeUuid":"` + childNodeUUID + `","relationshipType":"DERIVED_FROM","confidence":0.95,"tier":1,"resolver":"android_motion_photo_xmp","evidenceJson":{"mobileRelationship":"MOTION_PHOTO_CONTAINS"}}`
	if reqs[0].Payload != want {
		t.Errorf("payload =\n  %s\nwant\n  %s", reqs[0].Payload, want)
	}
}

// TestSyncEvents_NodeDeletedGoldenPayload pins the delete payload: the
// server's NodeDeletedPayload carrying the node UUID the item was uploaded
// as, never the local ID.
func TestSyncEvents_NodeDeletedGoldenPayload(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	recordMedia(t, q, "content://media/external/images/media/30", parentNodeUUID)
	id := enqueueDelete(t, q, "content://media/external/images/media/30")

	if n := syncEvents(t, eng, 10); n != 1 {
		t.Fatalf("sent = %d, want 1", n)
	}
	reqs := srv.sent()
	if len(reqs) != 1 {
		t.Fatalf("server saw %d requests, want 1", len(reqs))
	}
	if want := `{"nodeUuid":"` + parentNodeUUID + `"}`; reqs[0].Payload != want {
		t.Errorf("payload = %s, want %s", reqs[0].Payload, want)
	}
	if reqs[0].EventType != EventNodeDeleted {
		t.Errorf("eventType = %s, want %s", reqs[0].EventType, EventNodeDeleted)
	}
	if got := eventRow(t, q, id).Status; got != queue.EventSent {
		t.Errorf("status = %s, want SENT", got)
	}
}

// TestSyncEvents_LegacyQueuedRows: rows queued by earlier releases keep
// working -- the fmt-built lineage payload with "ph://" iOS identifiers
// whose uploads were recorded under the bare identifier, and a delete that
// carried a raw node UUID instead of a local ID.
func TestSyncEvents_LegacyQueuedRows(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	recordMedia(t, q, "AAAA-1111/L0/001", parentNodeUUID)
	recordMedia(t, q, "BBBB-2222/L0/001", childNodeUUID)
	enqueueRaw(t, q, EventEdgeAttached,
		`{"parentLocalId":"ph://AAAA-1111/L0/001","childLocalId":"ph://BBBB-2222/L0/001","relationship":"DERIVED_FROM","confidence":1.000000,"resolver":"ios_apple_proraw_pair"}`)
	enqueueRaw(t, q, EventNodeDeleted, `{"localId":"`+childNodeUUID+`"}`)
	// A delete queued with the "ph://" spelling resolves through the bare
	// identifier the upload was recorded under.
	enqueueRaw(t, q, EventNodeDeleted, `{"localId":"ph://AAAA-1111/L0/001"}`)

	if n := syncEvents(t, eng, 10); n != 3 {
		t.Fatalf("sent = %d, want 3", n)
	}
	reqs := srv.sent()
	if len(reqs) != 3 {
		t.Fatalf("server saw %d requests, want 3", len(reqs))
	}
	if want := `{"nodeUuid":"` + parentNodeUUID + `"}`; reqs[2].Payload != want {
		t.Errorf("legacy ph:// delete payload = %s, want %s", reqs[2].Payload, want)
	}
	if !strings.Contains(reqs[0].Payload, `"sourceNodeUuid":"`+parentNodeUUID+`","targetNodeUuid":"`+childNodeUUID+`"`) {
		t.Errorf("legacy edge payload = %s", reqs[0].Payload)
	}
	if want := `{"nodeUuid":"` + childNodeUUID + `"}`; reqs[1].Payload != want {
		t.Errorf("legacy delete payload = %s, want %s", reqs[1].Payload, want)
	}
}

// TestSyncEvents_EdgeDeferredUntilBothEndpointsUploaded: an edge whose child
// has no node UUID yet is not sent, stays PENDING and does not use up a
// retry; once the child is uploaded the same event goes out.
func TestSyncEvents_EdgeDeferredUntilBothEndpointsUploaded(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	recordMedia(t, q, "local-parent", parentNodeUUID)
	recordMedia(t, q, "local-child", "")
	id := enqueueLineage(t, q, LineageIntent{
		ParentLocalID: "local-parent", ChildLocalID: "local-child",
		Relationship: "DERIVED_FROM", Confidence: 1.0, Resolver: "android_camera_pair",
	})

	// Several passes: none may send it or count a failed attempt.
	for i := 0; i < 6; i++ {
		if n := syncEvents(t, eng, 10); n != 0 {
			t.Fatalf("pass %d: sent = %d, want 0", i, n)
		}
		makeClaimable(t, q, id)
	}
	if len(srv.sent()) != 0 {
		t.Fatalf("server saw %d requests for a deferred edge, want 0", len(srv.sent()))
	}
	ev := eventRow(t, q, id)
	if ev.Status != queue.EventPending || ev.RetryCount != 0 {
		t.Fatalf("deferred edge: status=%s retryCount=%d, want PENDING/0", ev.Status, ev.RetryCount)
	}
	if !strings.Contains(ev.ErrorMsg, "child") {
		t.Errorf("deferral reason %q does not name the missing endpoint", ev.ErrorMsg)
	}

	recordMedia(t, q, "local-child", childNodeUUID)
	if n := syncEvents(t, eng, 10); n != 1 {
		t.Fatalf("after upload: sent = %d, want 1", n)
	}
	if got := eventRow(t, q, id).Status; got != queue.EventSent {
		t.Errorf("status = %s, want SENT", got)
	}
}

// TestSyncEvents_EdgeResolvesNodeFromCompletedUpload: a node UUID that only
// the upload queue knows (local_media_state not yet updated) still resolves.
func TestSyncEvents_EdgeResolvesNodeFromCompletedUpload(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	recordMedia(t, q, "local-parent", parentNodeUUID)
	const hash = "c0ffee00000000000000000000000000000000000000000000000000000000c0" // pragma: allowlist secret
	if err := q.RecordLocalMedia("local-child", "", hash, "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia: %v", err)
	}
	upID, err := q.EnqueueUpload(&queue.UploadItem{LocalPath: "/x", TargetFilename: "x.jpg", Blake3Hash: hash})
	if err != nil {
		t.Fatalf("EnqueueUpload: %v", err)
	}
	if _, err := q.DB().Exec(`UPDATE upload_queue SET status = 'COMPLETED', node_uuid = ? WHERE id = ?`, childNodeUUID, upID); err != nil {
		t.Fatalf("complete upload: %v", err)
	}
	enqueueLineage(t, q, LineageIntent{
		ParentLocalID: "local-parent", ChildLocalID: "local-child",
		Relationship: "DERIVED_FROM", Confidence: 1.0, Resolver: "r",
	})
	if n := syncEvents(t, eng, 10); n != 1 {
		t.Fatalf("sent = %d, want 1", n)
	}
	if p := srv.sent()[0].Payload; !strings.Contains(p, `"targetNodeUuid":"`+childNodeUUID+`"`) {
		t.Errorf("payload = %s", p)
	}
}

// TestSyncEvents_DeferredEventsDoNotStarveQueue: deferred edges at the head
// of the queue rotate behind never-attempted events, so a small batch still
// reaches a dispatchable event queued after them.
func TestSyncEvents_DeferredEventsDoNotStarveQueue(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	recordMedia(t, q, "deleted-item", parentNodeUUID)
	var deferred []int64
	for i := 0; i < 3; i++ {
		deferred = append(deferred, enqueueLineage(t, q, LineageIntent{
			ParentLocalID: "never-uploaded-parent", ChildLocalID: "never-uploaded-child",
			Relationship: "DERIVED_FROM", Confidence: 1.0, Resolver: "r",
		}))
	}
	// Defer all three once, then let the backoff elapse for them.
	if n := syncEvents(t, eng, 3); n != 0 {
		t.Fatalf("sent = %d, want 0", n)
	}
	for _, id := range deferred {
		makeClaimable(t, q, id)
	}
	del := enqueueDelete(t, q, "deleted-item")

	if n := syncEvents(t, eng, 1); n != 1 {
		t.Fatalf("batch of 1 after deferred edges: sent = %d, want 1 (the delete)", n)
	}
	if got := eventRow(t, q, del).Status; got != queue.EventSent {
		t.Errorf("delete status = %s, want SENT", got)
	}
	if len(srv.sent()) != 1 {
		t.Errorf("server saw %d requests, want 1", len(srv.sent()))
	}
}

// TestSyncEvents_DeferredEdgeExpires: an edge still waiting for its media
// after EventDeferralMaxAge is FAILED with a clear reason, not kept forever.
func TestSyncEvents_DeferredEdgeExpires(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	recordMedia(t, q, "local-parent", parentNodeUUID)
	id := enqueueLineage(t, q, LineageIntent{
		ParentLocalID: "local-parent", ChildLocalID: "never-uploaded",
		Relationship: "DERIVED_FROM", Confidence: 1.0, Resolver: "r",
	})
	old := time.Now().Add(-EventDeferralMaxAge - time.Hour).Unix()
	if _, err := q.DB().Exec(`UPDATE event_queue SET created_at_unix = ? WHERE id = ?`, old, id); err != nil {
		t.Fatalf("age event: %v", err)
	}
	syncEvents(t, eng, 10)
	ev := eventRow(t, q, id)
	if ev.Status != queue.EventFailed || !strings.Contains(ev.ErrorMsg, "never reached the server") {
		t.Fatalf("expired edge: status=%s err=%q, want FAILED with an expiry reason", ev.Status, ev.ErrorMsg)
	}
	if len(srv.sent()) != 0 {
		t.Errorf("server saw %d requests, want 0", len(srv.sent()))
	}
}

// TestSyncEvents_DeleteNotSentForNeverUploadedMedia: a delete for an item
// with no local record, or one recorded but never uploaded, is SKIPPED and
// never reaches the server.
func TestSyncEvents_DeleteNotSentForNeverUploadedMedia(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	recordMedia(t, q, "recorded-not-uploaded", "")
	unknown := enqueueDelete(t, q, "content://media/external/images/media/404")
	notUploaded := enqueueDelete(t, q, "recorded-not-uploaded")

	if n := syncEvents(t, eng, 10); n != 0 {
		t.Fatalf("sent = %d, want 0", n)
	}
	if len(srv.sent()) != 0 {
		t.Fatalf("server saw %d requests, want 0", len(srv.sent()))
	}
	for _, id := range []int64{unknown, notUploaded} {
		ev := eventRow(t, q, id)
		if ev.Status != queue.EventSkipped || !strings.Contains(ev.ErrorMsg, "delete not sent") {
			t.Errorf("event %d: status=%s err=%q, want SKIPPED with a reason", id, ev.Status, ev.ErrorMsg)
		}
	}
}

// TestSyncEvents_DeleteWaitsForInFlightUpload: a delete for an item whose
// upload is still queued waits for it instead of being dropped, then goes
// out with the uploaded node's UUID.
func TestSyncEvents_DeleteWaitsForInFlightUpload(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	const hash = "d00d0000000000000000000000000000000000000000000000000000000000d0" // pragma: allowlist secret
	if err := q.RecordLocalMedia("uploading", "", hash, "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia: %v", err)
	}
	if _, err := q.EnqueueUpload(&queue.UploadItem{LocalPath: "/x", TargetFilename: "x.jpg", Blake3Hash: hash}); err != nil {
		t.Fatalf("EnqueueUpload: %v", err)
	}
	id := enqueueDelete(t, q, "uploading")
	syncEvents(t, eng, 10)
	if ev := eventRow(t, q, id); ev.Status != queue.EventPending || ev.RetryCount != 0 {
		t.Fatalf("delete during upload: status=%s retry=%d, want PENDING/0", ev.Status, ev.RetryCount)
	}
	if err := q.UpdateLocalMediaNodeUUID(hash, parentNodeUUID); err != nil {
		t.Fatalf("UpdateLocalMediaNodeUUID: %v", err)
	}
	makeClaimable(t, q, id)
	if n := syncEvents(t, eng, 10); n != 1 {
		t.Fatalf("sent = %d, want 1", n)
	}
	if p := srv.sent()[0].Payload; p != `{"nodeUuid":"`+parentNodeUUID+`"}` {
		t.Errorf("payload = %s", p)
	}
}

// TestSyncEvents_DeleteForStalledUploadExpires: a delete that waited for an
// upload that never completed is SKIPPED after EventDeferralMaxAge, with a
// reason that names the stalled upload rather than "never uploaded".
func TestSyncEvents_DeleteForStalledUploadExpires(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	const hash = "d00d0000000000000000000000000000000000000000000000000000000000d1" // pragma: allowlist secret
	if err := q.RecordLocalMedia("stalled", "", hash, "ACTIVE"); err != nil {
		t.Fatalf("RecordLocalMedia: %v", err)
	}
	if _, err := q.EnqueueUpload(&queue.UploadItem{LocalPath: "/y", TargetFilename: "y.jpg", Blake3Hash: hash}); err != nil {
		t.Fatalf("EnqueueUpload: %v", err)
	}
	id := enqueueDelete(t, q, "stalled")
	old := time.Now().Add(-EventDeferralMaxAge - time.Hour).Unix()
	if _, err := q.DB().Exec(`UPDATE event_queue SET created_at_unix = ? WHERE id = ?`, old, id); err != nil {
		t.Fatalf("age event: %v", err)
	}
	syncEvents(t, eng, 10)
	ev := eventRow(t, q, id)
	if ev.Status != queue.EventSkipped || !strings.Contains(ev.ErrorMsg, "did not complete within") {
		t.Fatalf("stalled delete: status=%s err=%q, want SKIPPED naming the stalled upload", ev.Status, ev.ErrorMsg)
	}
	if strings.Contains(ev.ErrorMsg, "never uploaded") {
		t.Errorf("stalled delete reason %q must not claim the item was never uploaded", ev.ErrorMsg)
	}
	if len(srv.sent()) != 0 {
		t.Errorf("server saw %d requests, want 0", len(srv.sent()))
	}
}

// TestSyncEvents_DeleteNotSentForOffloadedMedia: reclaim must never trash
// the server master. A delete for reclaimed media is SKIPPED, including when
// the reclaimed item is a different local item with the same server node.
func TestSyncEvents_DeleteNotSentForOffloadedMedia(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	recordMedia(t, q, "reclaimed", parentNodeUUID)
	if err := q.SetMediaOffloaded("reclaimed", true); err != nil {
		t.Fatalf("SetMediaOffloaded: %v", err)
	}
	recordMedia(t, q, "reclaimed-twin", childNodeUUID)
	recordMedia(t, q, "duplicate-of-twin", childNodeUUID)
	if err := q.SetMediaOffloaded("reclaimed-twin", true); err != nil {
		t.Fatalf("SetMediaOffloaded: %v", err)
	}

	direct := enqueueDelete(t, q, "reclaimed")
	viaSharedNode := enqueueDelete(t, q, "duplicate-of-twin")
	if n := syncEvents(t, eng, 10); n != 0 {
		t.Fatalf("sent = %d, want 0", n)
	}
	if len(srv.sent()) != 0 {
		t.Fatalf("server saw %d requests, want 0", len(srv.sent()))
	}
	for _, id := range []int64{direct, viaSharedNode} {
		ev := eventRow(t, q, id)
		if ev.Status != queue.EventSkipped || !strings.Contains(ev.ErrorMsg, "offloaded") {
			t.Errorf("event %d: status=%s err=%q, want SKIPPED (offloaded)", id, ev.Status, ev.ErrorMsg)
		}
	}
}

// TestSyncEvents_EdgeNeverSentWhenServerWouldRefuse: confidence below the
// server's 0.50 floor, an unknown relationship and a self-loop are all
// refused fatally by the server after a 202, so they are FAILED locally and
// never sent.
func TestSyncEvents_EdgeNeverSentWhenServerWouldRefuse(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	recordMedia(t, q, "p", parentNodeUUID)
	recordMedia(t, q, "c", childNodeUUID)
	recordMedia(t, q, "c-dup", childNodeUUID)

	lowConf := enqueueLineage(t, q, LineageIntent{ParentLocalID: "p", ChildLocalID: "c", Relationship: "DERIVED_FROM", Confidence: 0.49, Resolver: "r"})
	unknownRel := enqueueLineage(t, q, LineageIntent{ParentLocalID: "p", ChildLocalID: "c", Relationship: "SIBLING_OF", Confidence: 1.0, Resolver: "r"})
	selfLoop := enqueueLineage(t, q, LineageIntent{ParentLocalID: "c", ChildLocalID: "c-dup", Relationship: "DERIVED_FROM", Confidence: 1.0, Resolver: "r"})
	malformed := enqueueRaw(t, q, EventEdgeAttached, `{"child":"`+childNodeUUID+`"}`)

	if n := syncEvents(t, eng, 10); n != 0 {
		t.Fatalf("sent = %d, want 0", n)
	}
	if len(srv.sent()) != 0 {
		t.Fatalf("server saw %d requests, want 0", len(srv.sent()))
	}
	for name, c := range map[string]struct {
		id   int64
		want string
	}{
		"confidence below 0.50": {lowConf, "below the server's minimum"},
		"unknown relationship":  {unknownRel, "unknown relationship"},
		"self loop":             {selfLoop, "same node"},
		"malformed":             {malformed, "malformed"},
	} {
		ev := eventRow(t, q, c.id)
		if ev.Status != queue.EventFailed || !strings.Contains(ev.ErrorMsg, c.want) {
			t.Errorf("%s: status=%s err=%q, want FAILED containing %q", name, ev.Status, ev.ErrorMsg, c.want)
		}
	}
}

// TestSyncEvents_EdgeConfidenceClamped: a confidence at the floor is sent
// unchanged; one above 1.00 (only possible from a hand-written row) is sent
// as 1.
func TestSyncEvents_EdgeConfidenceClamped(t *testing.T) {
	eng, q, srv := newEventTestEngine(t)
	recordMedia(t, q, "p", parentNodeUUID)
	recordMedia(t, q, "c", childNodeUUID)
	enqueueRaw(t, q, EventEdgeAttached, `{"parentLocalId":"p","childLocalId":"c","relationship":"DERIVED_FROM","confidence":0.5,"resolver":"r"}`)
	enqueueRaw(t, q, EventEdgeAttached, `{"parentLocalId":"p","childLocalId":"c","relationship":"DERIVED_FROM","confidence":1.7,"resolver":"r"}`)
	syncEvents(t, eng, 10)
	reqs := srv.sent()
	if len(reqs) != 2 {
		t.Fatalf("server saw %d requests, want 2", len(reqs))
	}
	if !strings.Contains(reqs[0].Payload, `"confidence":0.5,`) || !strings.Contains(reqs[1].Payload, `"confidence":1,`) {
		t.Errorf("payloads = %s / %s", reqs[0].Payload, reqs[1].Payload)
	}
}

// TestSyncEvents_ServerRejectionTerminalVsRetryable: a 4xx answer (other
// than 401/403/408/429) ends the event as FAILED with the status and body
// after one attempt; 5xx and the transient 4xx codes keep it PENDING with a
// retry counted.
func TestSyncEvents_ServerRejectionTerminalVsRetryable(t *testing.T) {
	cases := []struct {
		status   int
		terminal bool
	}{
		{http.StatusBadRequest, true},
		{http.StatusConflict, true},
		{http.StatusUnprocessableEntity, true},
		{http.StatusUnauthorized, false},
		{http.StatusForbidden, false},
		{http.StatusRequestTimeout, false},
		{http.StatusTooManyRequests, false},
		{http.StatusInternalServerError, false},
		{http.StatusServiceUnavailable, false},
	}
	for _, c := range cases {
		t.Run(http.StatusText(c.status), func(t *testing.T) {
			eng, q, srv := newEventTestEngine(t)
			srv.status = c.status
			srv.body = `{"title":"rejected","detail":"bad payload"}`
			recordMedia(t, q, "item", parentNodeUUID)
			id := enqueueDelete(t, q, "item")

			if n := syncEvents(t, eng, 10); n != 0 {
				t.Fatalf("sent = %d, want 0", n)
			}
			ev := eventRow(t, q, id)
			if c.terminal {
				if ev.Status != queue.EventFailed {
					t.Fatalf("status = %s, want FAILED", ev.Status)
				}
				if !strings.Contains(ev.ErrorMsg, fmt.Sprintf("http %d", c.status)) ||
					!strings.Contains(ev.ErrorMsg, "bad payload") {
					t.Errorf("error_msg %q lacks the status and body", ev.ErrorMsg)
				}
				// Terminal: a later sync never resends it.
				makeClaimable(t, q, id)
				syncEvents(t, eng, 10)
				if len(srv.sent()) != 1 {
					t.Errorf("server saw %d requests, want exactly 1", len(srv.sent()))
				}
				return
			}
			if ev.Status != queue.EventPending || ev.RetryCount != 1 {
				t.Fatalf("status=%s retry=%d, want PENDING/1", ev.Status, ev.RetryCount)
			}
		})
	}
}
