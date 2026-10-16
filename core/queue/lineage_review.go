package queue

import (
	"database/sql"
	"errors"
	"fmt"
)

// LineageDecision is the user's verdict on a proposed lineage pair.
type LineageDecision string

const (
	// LineageConfirmed: the user confirmed the pair and its
	// EVENT_EDGE_ATTACHED was queued.
	LineageConfirmed LineageDecision = "CONFIRMED"
	// LineageRejected: the user rejected the pair; it must never be
	// proposed or sent again.
	LineageRejected LineageDecision = "REJECTED"
)

// LineageReview is one recorded decision, keyed by the pair's local IDs
// (parent first). EventUUID is set for a CONFIRMED pair only.
type LineageReview struct {
	ParentLocalID string          `json:"parentLocalId"`
	ChildLocalID  string          `json:"childLocalId"`
	Decision      LineageDecision `json:"decision"`
	Relationship  string          `json:"relationship,omitempty"`
	Resolver      string          `json:"resolver,omitempty"`
	Confidence    float64         `json:"confidence"`
	EventUUID     string          `json:"eventUuid,omitempty"`
	DecidedAtUnix int64           `json:"decidedAtUnix"`
}

// ErrLineagePairRequired is returned when either local ID of a pair is
// empty.
var ErrLineagePairRequired = errors.New("parent and child local IDs are required")

// ErrLineageAlreadyConfirmed is returned by RejectLineagePair for a pair
// the user already confirmed whose edge is still queued or was sent: only
// a reviewer on the server can take it back.
var ErrLineageAlreadyConfirmed = errors.New("lineage pair was already confirmed")

// deadEventStatuses are the event_queue statuses a confirmed pair's edge
// can end in without reaching the server. Such a pair is decided again:
// ListLineageReviews leaves it out (so the review screen offers it), a new
// confirm re-queues its edge, and a reject is allowed.
const deadEventStatuses = `('FAILED', 'SKIPPED')`

// ConfirmLineagePair records that the user confirmed the pair and, in the
// same transaction, queues payloadJSON as an event of eventType, so a
// confirmed pair always has exactly one live edge. Confirming a pair that
// is already CONFIRMED queues nothing and returns the existing event's UUID
// with created false -- unless that event ended FAILED or SKIPPED, in which
// case a new one is queued and recorded. Confirming a pair the user had
// rejected overrides the rejection.
func (q *Queue) ConfirmLineagePair(r LineageReview, eventType, payloadJSON string) (eventUUID string, created bool, err error) {
	if r.ParentLocalID == "" || r.ChildLocalID == "" {
		return "", false, ErrLineagePairRequired
	}
	if len(payloadJSON) > MaxEventPayloadBytes {
		return "", false, fmt.Errorf("%w: %d > %d", ErrPayloadTooLarge, len(payloadJSON), MaxEventPayloadBytes)
	}
	q.mu.Lock()
	defer q.mu.Unlock()

	tx, err := q.db.Begin()
	if err != nil {
		return "", false, fmt.Errorf("confirm lineage pair: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	var decision, existing string
	err = tx.QueryRow(`
	SELECT decision, event_uuid FROM lineage_review
	WHERE parent_local_id = ? AND child_local_id = ?`,
		r.ParentLocalID, r.ChildLocalID).Scan(&decision, &existing)
	switch {
	case err == nil && LineageDecision(decision) == LineageConfirmed && existing != "":
		var dead bool
		if err := tx.QueryRow(`
		SELECT EXISTS (SELECT 1 FROM event_queue
		               WHERE event_uuid = ? AND status IN `+deadEventStatuses+`)`,
			existing).Scan(&dead); err != nil {
			return "", false, fmt.Errorf("confirm lineage pair: %w", err)
		}
		if !dead {
			return existing, false, nil
		}
	case err != nil && !errors.Is(err, sql.ErrNoRows):
		return "", false, fmt.Errorf("confirm lineage pair: %w", err)
	}

	eventUUID, err = insertEvent(tx, eventType, payloadJSON)
	if err != nil {
		return "", false, err
	}
	now := nowUnix()
	if _, err := tx.Exec(`
	INSERT INTO lineage_review (
		parent_local_id, child_local_id, decision, relationship, resolver,
		confidence, event_uuid, created_at_unix, updated_at_unix
	) VALUES (?, ?, 'CONFIRMED', ?, ?, ?, ?, ?, ?)
	ON CONFLICT(parent_local_id, child_local_id) DO UPDATE SET
		decision = 'CONFIRMED',
		relationship = excluded.relationship,
		resolver = excluded.resolver,
		confidence = excluded.confidence,
		event_uuid = excluded.event_uuid,
		updated_at_unix = excluded.updated_at_unix
	`, r.ParentLocalID, r.ChildLocalID, r.Relationship, r.Resolver, r.Confidence, eventUUID, now, now); err != nil {
		return "", false, fmt.Errorf("confirm lineage pair: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return "", false, fmt.Errorf("confirm lineage pair: %w", err)
	}
	return eventUUID, true, nil
}

// RejectLineagePair records that the user rejected the pair. Rejecting it
// again is a no-op; rejecting a CONFIRMED pair is ErrLineageAlreadyConfirmed,
// unless its edge ended FAILED or SKIPPED and so never reached the server.
func (q *Queue) RejectLineagePair(r LineageReview) error {
	if r.ParentLocalID == "" || r.ChildLocalID == "" {
		return ErrLineagePairRequired
	}
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	res, err := q.db.Exec(`
	INSERT INTO lineage_review (
		parent_local_id, child_local_id, decision, relationship, resolver,
		confidence, event_uuid, created_at_unix, updated_at_unix
	) VALUES (?, ?, 'REJECTED', ?, ?, ?, '', ?, ?)
	ON CONFLICT(parent_local_id, child_local_id) DO UPDATE SET
		decision = 'REJECTED',
		updated_at_unix = excluded.updated_at_unix
	WHERE lineage_review.decision <> 'CONFIRMED'
	   OR EXISTS (SELECT 1 FROM event_queue e
	              WHERE e.event_uuid = lineage_review.event_uuid
	                AND e.status IN `+deadEventStatuses+`)
	`, r.ParentLocalID, r.ChildLocalID, r.Relationship, r.Resolver, r.Confidence, now, now)
	if err != nil {
		return fmt.Errorf("reject lineage pair: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("reject lineage pair: %w", err)
	}
	if n == 0 {
		return ErrLineageAlreadyConfirmed
	}
	return nil
}

// GetLineageReview returns the decision recorded for the pair, or
// sql.ErrNoRows when the user has not decided it.
func (q *Queue) GetLineageReview(parentLocalID, childLocalID string) (*LineageReview, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	var r LineageReview
	var decision string
	err := q.db.QueryRow(`
	SELECT parent_local_id, child_local_id, decision, relationship, resolver,
	       confidence, event_uuid, updated_at_unix
	FROM lineage_review WHERE parent_local_id = ? AND child_local_id = ?`,
		parentLocalID, childLocalID).Scan(&r.ParentLocalID, &r.ChildLocalID, &decision,
		&r.Relationship, &r.Resolver, &r.Confidence, &r.EventUUID, &r.DecidedAtUnix)
	if err != nil {
		return nil, err
	}
	r.Decision = LineageDecision(decision)
	return &r, nil
}

// ListLineageReviews returns every recorded decision, most recent first.
// A CONFIRMED pair whose edge ended FAILED or SKIPPED is left out, so the
// review screen offers it again (see deadEventStatuses).
func (q *Queue) ListLineageReviews() ([]*LineageReview, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	rows, err := q.db.Query(`
	SELECT parent_local_id, child_local_id, decision, relationship, resolver,
	       confidence, event_uuid, updated_at_unix
	FROM lineage_review r
	WHERE NOT (r.decision = 'CONFIRMED' AND EXISTS (
	      SELECT 1 FROM event_queue e
	      WHERE e.event_uuid = r.event_uuid AND e.status IN ` + deadEventStatuses + `))
	ORDER BY updated_at_unix DESC, parent_local_id ASC, child_local_id ASC`)
	if err != nil {
		return nil, fmt.Errorf("list lineage reviews: %w", err)
	}
	defer rows.Close()

	out := []*LineageReview{}
	for rows.Next() {
		var r LineageReview
		var decision string
		if err := rows.Scan(&r.ParentLocalID, &r.ChildLocalID, &decision, &r.Relationship,
			&r.Resolver, &r.Confidence, &r.EventUUID, &r.DecidedAtUnix); err != nil {
			return nil, fmt.Errorf("scan lineage review: %w", err)
		}
		r.Decision = LineageDecision(decision)
		out = append(out, &r)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate lineage reviews: %w", err)
	}
	return out, nil
}
