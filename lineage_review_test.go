package branchdam

import (
	"encoding/json"
	"path/filepath"
	"testing"

	"github.com/s3ntin3l8/branchdam-mobile/core/engine"
	"github.com/s3ntin3l8/branchdam-mobile/core/queue"
)

// queuedIntent reads back the lineage intent queued under eventUUID.
func queuedIntent(t *testing.T, e *Engine, eventUUID string) engine.LineageIntent {
	t.Helper()
	var payload string
	if err := e.queue.DB().QueryRow(`SELECT payload_json FROM event_queue WHERE event_uuid = ?`, eventUUID).Scan(&payload); err != nil {
		t.Fatalf("read queued event %s: %v", eventUUID, err)
	}
	var in engine.LineageIntent
	if err := json.Unmarshal([]byte(payload), &in); err != nil {
		t.Fatalf("decode %s: %v", payload, err)
	}
	return in
}

func countQueuedEvents(t *testing.T, e *Engine) int64 {
	t.Helper()
	n, err := e.queue.CountPendingEvents()
	if err != nil {
		t.Fatalf("CountPendingEvents: %v", err)
	}
	return n
}

// TestEnqueueLineageEvent_GuessNeedsReview: the automatic path takes exact
// (1.00) pairs only. A 0.95 timestamp-proximity or in-phone-edit guess is
// REVIEW_REQUIRED and nothing is queued.
func TestEnqueueLineageEvent_GuessNeedsReview(t *testing.T) {
	e, _ := newTestEngineWithServer(t)

	for _, c := range []Confidence{ConfidenceProximity, ConfidenceEdit, 0.999, 0.5} {
		_, err := e.EnqueueLineageEvent("content://raw/1", "content://jpg/2", "DERIVED_FROM", "android_camera_pair", c)
		if got := errorCode(t, err); got != CodeReviewRequired {
			t.Errorf("confidence %v: code %s, want REVIEW_REQUIRED", float64(c), got)
		}
	}
	if n := countQueuedEvents(t, e); n != 0 {
		t.Fatalf("queued %d events for unreviewed guesses", n)
	}

	// Invariant 4: an exact pair (DNG + Ultra HDR JPEG, Motion Photo) is
	// still sent automatically, and is not marked as reviewed.
	id, err := e.EnqueueLineageEvent("content://raw/1", "content://jpg/1", "DERIVED_FROM", "android_camera_pair", ConfidenceExact)
	if err != nil {
		t.Fatalf("exact pair: %v", err)
	}
	if in := queuedIntent(t, e, id); in.Confidence != 1.0 || in.Reviewed {
		t.Errorf("exact intent = %+v", in)
	}
	id, err = e.EnqueueLineageEvent("content://jpg/3", "content://jpg/3/microvideo", "MOTION_PHOTO_CONTAINS", "android_motion_photo_xmp", ConfidenceExact)
	if err != nil || id == "" {
		t.Fatalf("motion photo: %q, %v", id, err)
	}
}

// TestConfirmLineageEdge_KeepsConfidence: a confirmation queues the edge at
// the resolver's own confidence, marked as reviewed, and a second confirm
// of the same pair queues nothing new.
func TestConfirmLineageEdge_KeepsConfidence(t *testing.T) {
	e, _ := newTestEngineWithServer(t)

	id, err := e.ConfirmLineageEdge("ph://master", "ph://edit", "DERIVED_FROM", "in_phone_photomator", ConfidenceEdit)
	if err != nil {
		t.Fatalf("ConfirmLineageEdge: %v", err)
	}
	in := queuedIntent(t, e, id)
	if in.Confidence != 0.95 || !in.Reviewed || in.ParentLocalID != "ph://master" ||
		in.ChildLocalID != "ph://edit" || in.Resolver != "in_phone_photomator" {
		t.Errorf("confirmed intent = %+v, want 0.95 reviewed", in)
	}
	again, err := e.ConfirmLineageEdge("ph://master", "ph://edit", "DERIVED_FROM", "in_phone_photomator", ConfidenceEdit)
	if err != nil || again != id {
		t.Fatalf("second confirm = %q, %v; want %q", again, err, id)
	}
	if n := countQueuedEvents(t, e); n != 1 {
		t.Fatalf("queued %d events, want 1", n)
	}
}

func TestConfirmLineageEdge_InvalidInput(t *testing.T) {
	e, _ := newTestEngineWithServer(t)
	cases := map[string]func() error{
		"below server floor": func() error {
			_, err := e.ConfirmLineageEdge("a", "b", "DERIVED_FROM", "r", 0.49)
			return err
		},
		"same endpoints": func() error {
			_, err := e.ConfirmLineageEdge("a", "a", "DERIVED_FROM", "r", 0.95)
			return err
		},
		"unknown relationship": func() error {
			_, err := e.ConfirmLineageEdge("a", "b", "SIBLING_OF", "r", 0.95)
			return err
		},
		"missing parent": func() error {
			_, err := e.ConfirmLineageEdge("", "b", "DERIVED_FROM", "r", 0.95)
			return err
		},
		"reject missing child": func() error {
			return e.RejectLineageEdge("a", "", "DERIVED_FROM", "r", 0.95)
		},
	}
	for name, f := range cases {
		if got := errorCode(t, f()); got != CodeInvalidInput {
			t.Errorf("%s: code %s, want INVALID_INPUT", name, got)
		}
	}
	if n := countQueuedEvents(t, e); n != 0 {
		t.Fatalf("queued %d events for invalid input", n)
	}
}

// TestBindingLineageReviewRoundTrip covers the review FFI surface end to
// end: a rejection survives the engine being closed and reopened (a new
// app process), and a confirmed pair can't be rejected from the device.
func TestBindingLineageReviewRoundTrip(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "engine.db")
	if err := BindingOpen(dbPath, "http://localhost", "", "test", "test", "localhost"); err != nil {
		t.Fatalf("BindingOpen: %v", err)
	}
	defer BindingClose()

	if err := BindingRejectLineageEdge("content://raw/1", "content://jpg/9", "DERIVED_FROM", "android_camera_pair", 0.95); err != nil {
		t.Fatalf("BindingRejectLineageEdge: %v", err)
	}
	if _, err := BindingEnqueueLineageEvent("content://raw/2", "content://jpg/8", "DERIVED_FROM", "android_camera_pair", 0.95); errorCode(t, err) != CodeReviewRequired {
		t.Fatalf("auto-emitting a guess: err = %v, want REVIEW_REQUIRED", err)
	}
	eventID, err := BindingConfirmLineageEdge("content://raw/2", "content://jpg/8", "DERIVED_FROM", "android_camera_pair", 0.95)
	if err != nil || eventID == "" {
		t.Fatalf("BindingConfirmLineageEdge = %q, %v", eventID, err)
	}

	// Process death.
	if err := BindingClose(); err != nil {
		t.Fatal(err)
	}
	if err := BindingOpen(dbPath, "http://localhost", "", "test", "test", "localhost"); err != nil {
		t.Fatalf("BindingOpen (reopen): %v", err)
	}

	js, err := BindingListLineageReviews()
	if err != nil {
		t.Fatalf("BindingListLineageReviews: %v", err)
	}
	var reviews []queue.LineageReview
	if err := json.Unmarshal([]byte(js), &reviews); err != nil {
		t.Fatalf("decode %s: %v", js, err)
	}
	got := map[string]queue.LineageReview{}
	for _, r := range reviews {
		got[r.ParentLocalID+"|"+r.ChildLocalID] = r
	}
	if r := got["content://raw/1|content://jpg/9"]; len(reviews) != 2 || r.Decision != queue.LineageRejected {
		t.Fatalf("reviews after reopen = %s", js)
	}
	if r := got["content://raw/2|content://jpg/8"]; r.Decision != queue.LineageConfirmed || r.EventUUID != eventID || r.Confidence != 0.95 {
		t.Fatalf("confirmed review = %+v", r)
	}

	if err := BindingRejectLineageEdge("content://raw/2", "content://jpg/8", "DERIVED_FROM", "android_camera_pair", 0.95); errorCode(t, err) != CodeInvalidInput {
		t.Fatalf("rejecting a confirmed pair: err = %v, want INVALID_INPUT", err)
	}
}
