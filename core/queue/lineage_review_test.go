package queue

import (
	"database/sql"
	"errors"
	"path/filepath"
	"strings"
	"testing"
)

func pair(parent, child string) LineageReview {
	return LineageReview{
		ParentLocalID: parent,
		ChildLocalID:  child,
		Relationship:  "DERIVED_FROM",
		Resolver:      "android_camera_pair",
		Confidence:    0.95,
	}
}

func countEvents(t *testing.T, q *Queue) int {
	t.Helper()
	var n int
	if err := q.DB().QueryRow(`SELECT COUNT(*) FROM event_queue`).Scan(&n); err != nil {
		t.Fatalf("count events: %v", err)
	}
	return n
}

// TestRejectLineagePair_SurvivesReopen: a rejection is stored in the
// database, so a new process (a reopened queue) still knows about it.
func TestRejectLineagePair_SurvivesReopen(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "q.db")
	q, err := Open(dbPath)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := q.RejectLineagePair(pair("content://raw/1", "content://jpg/2")); err != nil {
		t.Fatalf("RejectLineagePair: %v", err)
	}
	// Rejecting again is a no-op, not an error.
	if err := q.RejectLineagePair(pair("content://raw/1", "content://jpg/2")); err != nil {
		t.Fatalf("second RejectLineagePair: %v", err)
	}
	if err := q.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	q2, err := Open(dbPath)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer q2.Close()
	r, err := q2.GetLineageReview("content://raw/1", "content://jpg/2")
	if err != nil {
		t.Fatalf("GetLineageReview after reopen: %v", err)
	}
	if r.Decision != LineageRejected || r.EventUUID != "" || r.Confidence != 0.95 {
		t.Errorf("review = %+v, want REJECTED without an event", r)
	}
	if n := countEvents(t, q2); n != 0 {
		t.Errorf("a rejection queued %d events", n)
	}
	// The reverse direction is a different pair.
	if _, err := q2.GetLineageReview("content://jpg/2", "content://raw/1"); !errors.Is(err, sql.ErrNoRows) {
		t.Errorf("reversed pair: err = %v, want sql.ErrNoRows", err)
	}
}

// TestConfirmLineagePair_QueuesExactlyOneEvent: confirming records the
// decision and queues the edge together; confirming again returns the
// same event instead of queueing a duplicate.
func TestConfirmLineagePair_QueuesExactlyOneEvent(t *testing.T) {
	q := newTestQueue(t)
	p := pair("ph://master", "ph://edit")

	uuid1, created, err := q.ConfirmLineagePair(p, "EVENT_EDGE_ATTACHED", `{"confidence":0.95}`)
	if err != nil || !created || uuid1 == "" {
		t.Fatalf("ConfirmLineagePair = %q, %v, %v", uuid1, created, err)
	}
	uuid2, created, err := q.ConfirmLineagePair(p, "EVENT_EDGE_ATTACHED", `{"confidence":0.95}`)
	if err != nil || created || uuid2 != uuid1 {
		t.Fatalf("second ConfirmLineagePair = %q, %v, %v; want %q, false, nil", uuid2, created, err, uuid1)
	}
	if n := countEvents(t, q); n != 1 {
		t.Fatalf("events = %d, want 1", n)
	}
	r, err := q.GetLineageReview("ph://master", "ph://edit")
	if err != nil {
		t.Fatalf("GetLineageReview: %v", err)
	}
	if r.Decision != LineageConfirmed || r.EventUUID != uuid1 {
		t.Errorf("review = %+v, want CONFIRMED with event %s", r, uuid1)
	}
	// The queued payload is exactly what the caller built: the queue
	// never touches the confidence.
	var payload string
	if err := q.DB().QueryRow(`SELECT payload_json FROM event_queue WHERE event_uuid = ?`, uuid1).Scan(&payload); err != nil {
		t.Fatalf("read payload: %v", err)
	}
	if payload != `{"confidence":0.95}` {
		t.Errorf("payload = %s", payload)
	}

	// A confirmed pair can't be rejected from the device any more.
	if err := q.RejectLineagePair(p); !errors.Is(err, ErrLineageAlreadyConfirmed) {
		t.Errorf("RejectLineagePair on a confirmed pair: err = %v, want ErrLineageAlreadyConfirmed", err)
	}
	if r, _ := q.GetLineageReview("ph://master", "ph://edit"); r == nil || r.Decision != LineageConfirmed {
		t.Errorf("decision changed by a refused reject: %+v", r)
	}
}

// TestConfirmLineagePair_OverridesRejection: a user who changes their mind
// can still confirm a pair they rejected.
func TestConfirmLineagePair_OverridesRejection(t *testing.T) {
	q := newTestQueue(t)
	p := pair("a", "b")
	if err := q.RejectLineagePair(p); err != nil {
		t.Fatalf("RejectLineagePair: %v", err)
	}
	uuid, created, err := q.ConfirmLineagePair(p, "EVENT_EDGE_ATTACHED", `{}`)
	if err != nil || !created || uuid == "" {
		t.Fatalf("ConfirmLineagePair = %q, %v, %v", uuid, created, err)
	}
	r, err := q.GetLineageReview("a", "b")
	if err != nil || r.Decision != LineageConfirmed || r.EventUUID != uuid {
		t.Fatalf("review = %+v, %v; want CONFIRMED", r, err)
	}
}

// TestConfirmLineagePair_AtomicOnFailure: when the event can't be queued,
// no decision is recorded either, so the pair is offered for review again.
func TestConfirmLineagePair_AtomicOnFailure(t *testing.T) {
	q := newTestQueue(t)
	big := `{"x":"` + strings.Repeat("a", MaxEventPayloadBytes) + `"}`
	if _, _, err := q.ConfirmLineagePair(pair("a", "b"), "EVENT_EDGE_ATTACHED", big); !errors.Is(err, ErrPayloadTooLarge) {
		t.Fatalf("err = %v, want ErrPayloadTooLarge", err)
	}
	if _, err := q.DB().Exec(`DROP TABLE event_queue`); err != nil {
		t.Fatalf("drop event_queue: %v", err)
	}
	if _, _, err := q.ConfirmLineagePair(pair("a", "b"), "EVENT_EDGE_ATTACHED", `{}`); err == nil {
		t.Fatal("ConfirmLineagePair succeeded without an event table")
	}
	if _, err := q.GetLineageReview("a", "b"); !errors.Is(err, sql.ErrNoRows) {
		t.Errorf("decision recorded although the event was not queued: err = %v", err)
	}
}

func TestLineagePair_RequiresBothIDs(t *testing.T) {
	q := newTestQueue(t)
	if err := q.RejectLineagePair(pair("", "b")); !errors.Is(err, ErrLineagePairRequired) {
		t.Errorf("reject: err = %v", err)
	}
	if _, _, err := q.ConfirmLineagePair(pair("a", ""), "EVENT_EDGE_ATTACHED", `{}`); !errors.Is(err, ErrLineagePairRequired) {
		t.Errorf("confirm: err = %v", err)
	}
}

func TestListLineageReviews(t *testing.T) {
	q := newTestQueue(t)
	if got, err := q.ListLineageReviews(); err != nil || len(got) != 0 {
		t.Fatalf("empty list = %v, %v", got, err)
	}
	if err := q.RejectLineagePair(pair("r1", "r2")); err != nil {
		t.Fatal(err)
	}
	if _, _, err := q.ConfirmLineagePair(pair("c1", "c2"), "EVENT_EDGE_ATTACHED", `{}`); err != nil {
		t.Fatal(err)
	}
	got, err := q.ListLineageReviews()
	if err != nil || len(got) != 2 {
		t.Fatalf("list = %v, %v", got, err)
	}
	decisions := map[string]LineageDecision{}
	for _, r := range got {
		decisions[r.ParentLocalID+"|"+r.ChildLocalID] = r.Decision
	}
	if decisions["r1|r2"] != LineageRejected || decisions["c1|c2"] != LineageConfirmed {
		t.Errorf("decisions = %v", decisions)
	}
}

// TestConfirmedPairWhoseEdgeDiedIsDecidedAgain: a confirmed pair's edge
// that ended FAILED or SKIPPED never reached the server, so the pair is
// offered again (left out of ListLineageReviews), a new confirm queues a
// fresh edge, and a reject is allowed. A live edge keeps the old rules.
func TestConfirmedPairWhoseEdgeDiedIsDecidedAgain(t *testing.T) {
	for _, status := range []string{"FAILED", "SKIPPED"} {
		t.Run(status, func(t *testing.T) {
			q := newTestQueue(t)
			p := pair("ph://master", "ph://edit")
			first, _, err := q.ConfirmLineagePair(p, "EVENT_EDGE_ATTACHED", `{"n":1}`)
			if err != nil {
				t.Fatalf("ConfirmLineagePair: %v", err)
			}
			if reviews, err := q.ListLineageReviews(); err != nil || len(reviews) != 1 {
				t.Fatalf("live confirmed pair: reviews = %v, %v; want it listed", reviews, err)
			}
			if _, err := q.DB().Exec(`UPDATE event_queue SET status = ? WHERE event_uuid = ?`, status, first); err != nil {
				t.Fatal(err)
			}

			reviews, err := q.ListLineageReviews()
			if err != nil || len(reviews) != 0 {
				t.Fatalf("reviews = %v, %v; a pair whose edge died must be offered again", reviews, err)
			}

			second, created, err := q.ConfirmLineagePair(p, "EVENT_EDGE_ATTACHED", `{"n":2}`)
			if err != nil || !created || second == first {
				t.Fatalf("re-confirm = %q, %v, %v; want a new event", second, created, err)
			}
			if n := countEvents(t, q); n != 2 {
				t.Errorf("events = %d, want the dead one plus a new one", n)
			}
			r, err := q.GetLineageReview("ph://master", "ph://edit")
			if err != nil || r.Decision != LineageConfirmed || r.EventUUID != second {
				t.Fatalf("review = %+v, %v; want CONFIRMED pointing at the new event", r, err)
			}

			// The new edge is live again: no reject, listed as decided.
			if err := q.RejectLineagePair(p); !errors.Is(err, ErrLineageAlreadyConfirmed) {
				t.Errorf("reject with a live edge: err = %v, want ErrLineageAlreadyConfirmed", err)
			}
			if _, err := q.DB().Exec(`UPDATE event_queue SET status = ? WHERE event_uuid = ?`, status, second); err != nil {
				t.Fatal(err)
			}
			if err := q.RejectLineagePair(p); err != nil {
				t.Fatalf("reject once the edge died: %v", err)
			}
			// EventUUID is CONFIRMED-only: the dead edge's UUID goes.
			if r, _ := q.GetLineageReview("ph://master", "ph://edit"); r == nil || r.Decision != LineageRejected || r.EventUUID != "" {
				t.Errorf("review = %+v, want REJECTED without an event", r)
			}
		})
	}
}
