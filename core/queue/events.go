package queue

import (
	"errors"
	"fmt"

	"github.com/google/uuid"
)

// MaxEventPayloadBytes caps the size of a single event's JSON payload.
// Beyond this, the writer blocks the single SQLite connection for too
// long; the limit is enforced in EnqueueEvent and surfaced to the FFI
// surface as a typed ErrPayloadTooLarge error.
const MaxEventPayloadBytes = 64 * 1024

// ErrPayloadTooLarge is returned by EnqueueEvent when payloadJSON exceeds
// MaxEventPayloadBytes. The branchdam FFI surface maps this to
// branchdam.Error{Code: "PAYLOAD_TOO_LARGE"}.
var ErrPayloadTooLarge = errors.New("event payload too large")

// EnqueueEvent inserts a new event into the event queue with a generated UUIDv7.
// The payload is rejected with ErrPayloadTooLarge if it exceeds
// MaxEventPayloadBytes; this prevents a single misbehaving caller from
// blocking the queue's single-connection serialisation.
func (q *Queue) EnqueueEvent(eventType, payloadJSON string) (string, error) {
	if len(payloadJSON) > MaxEventPayloadBytes {
		return "", fmt.Errorf("%w: %d > %d", ErrPayloadTooLarge, len(payloadJSON), MaxEventPayloadBytes)
	}

	q.mu.Lock()
	defer q.mu.Unlock()

	u, err := uuid.NewV7()
	if err != nil {
		return "", fmt.Errorf("failed to generate event uuid: %w", err)
	}
	eventUUID := u.String()
	now := nowUnix()

	query := `
	INSERT INTO event_queue (
		event_uuid, event_type, payload_json, status, retry_count,
		last_attempt_unix, error_msg, created_at_unix, updated_at_unix
	) VALUES (?, ?, ?, 'PENDING', 0, 0, '', ?, ?)
	`
	_, err = q.db.Exec(query, eventUUID, eventType, payloadJSON, now, now)
	if err != nil {
		return "", fmt.Errorf("failed to enqueue event: %w", err)
	}
	return eventUUID, nil
}

// ClaimPendingEvents atomically claims a batch of event items eligible
// for transmission. Atomic via UPDATE...RETURNING.
func (q *Queue) ClaimPendingEvents(limit int, retryBackoffSecs int64, maxRetries int) ([]*EventItem, error) {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	cutoff := now - retryBackoffSecs

	query := `
	UPDATE event_queue
	SET status = 'IN_PROGRESS',
	    last_attempt_unix = ?,
	    updated_at_unix    = ?
	WHERE id IN (
	  SELECT id FROM event_queue
	  WHERE status = 'PENDING'
	    AND retry_count < ?
	    AND (last_attempt_unix = 0 OR last_attempt_unix <= ?)
	  ORDER BY id ASC
	  LIMIT ?
	)
	RETURNING id, event_uuid, event_type, payload_json, status, retry_count,
	          last_attempt_unix, error_msg, created_at_unix, updated_at_unix
	`
	rows, err := q.db.Query(query, now, now, maxRetries, cutoff, limit)
	if err != nil {
		return nil, fmt.Errorf("failed to claim pending events: %w", err)
	}
	defer rows.Close()

	var items []*EventItem
	for rows.Next() {
		var item EventItem
		var statusStr string
		err := rows.Scan(
			&item.ID, &item.EventUUID, &item.EventType, &item.PayloadJSON, &statusStr,
			&item.RetryCount, &item.LastAttemptUnix, &item.ErrorMsg,
			&item.CreatedAtUnix, &item.UpdatedAtUnix,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan event row: %w", err)
		}
		item.Status = EventStatus(statusStr)
		items = append(items, &item)
	}

	return items, nil
}

// MarkEventSent marks the event as successfully dispatched.
func (q *Queue) MarkEventSent(id int64) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	query := `UPDATE event_queue SET status = 'SENT', error_msg = '', updated_at_unix = ? WHERE id = ?`
	_, err := q.db.Exec(query, now, id)
	return err
}

// MarkEventFailed records a failure for an event transmission attempt.
func (q *Queue) MarkEventFailed(id int64, errMsg string, maxRetries int) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	query := `
	UPDATE event_queue
	SET status = CASE WHEN retry_count + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
	    retry_count = retry_count + 1,
	    error_msg = ?,
	    last_attempt_unix = ?,
	    updated_at_unix = ?
	WHERE id = ?
	`
	_, err := q.db.Exec(query, maxRetries, errMsg, now, now, id)
	return err
}

// CountPendingEvents returns the number of pending event tasks.
func (q *Queue) CountPendingEvents() (int64, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	var count int64
	err := q.db.QueryRow(`SELECT COUNT(*) FROM event_queue WHERE status = 'PENDING'`).Scan(&count)
	return count, err
}
