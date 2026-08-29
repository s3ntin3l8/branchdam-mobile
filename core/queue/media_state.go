package queue

import (
	"database/sql"
	"errors"
	"fmt"
)

// RecordLocalMedia upserts local capture state indexed by local MediaStore ID / URI.
func (q *Queue) RecordLocalMedia(localID, nodeUUID, blake3Hash, lifecycleState string) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	query := `
	INSERT INTO local_media_state (
		local_id, node_uuid, blake3_hash, lifecycle_state, is_offloaded,
		created_at_unix, updated_at_unix
	) VALUES (?, ?, ?, ?, 0, ?, ?)
	ON CONFLICT(local_id) DO UPDATE SET
		node_uuid = excluded.node_uuid,
		blake3_hash = excluded.blake3_hash,
		lifecycle_state = excluded.lifecycle_state,
		updated_at_unix = excluded.updated_at_unix
	`
	_, err := q.db.Exec(query, localID, nodeUUID, blake3Hash, lifecycleState, now, now)
	return err
}

// SetMediaOffloaded flags a local media asset as offloaded (safe local delete).
func (q *Queue) SetMediaOffloaded(localID string, isOffloaded bool) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	offloadedInt := 0
	if isOffloaded {
		offloadedInt = 1
	}

	query := `UPDATE local_media_state SET is_offloaded = ?, updated_at_unix = ? WHERE local_id = ?`
	res, err := q.db.Exec(query, offloadedInt, now, localID)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return errors.New("media record not found")
	}
	return nil
}

// IsMediaOffloaded returns true if a local asset deletion was an intentional offload.
func (q *Queue) IsMediaOffloaded(localID string) (bool, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	var offloadedInt int
	err := q.db.QueryRow(`SELECT is_offloaded FROM local_media_state WHERE local_id = ?`, localID).Scan(&offloadedInt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return false, nil
		}
		return false, fmt.Errorf("query local media state failed: %w", err)
	}
	return offloadedInt == 1, nil
}

// GetMediaByLocalID retrieves local media state by local ID.
func (q *Queue) GetMediaByLocalID(localID string) (*LocalMediaState, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	var item LocalMediaState
	var isOffloadedInt int
	query := `SELECT local_id, node_uuid, blake3_hash, lifecycle_state, is_offloaded, created_at_unix, updated_at_unix FROM local_media_state WHERE local_id = ?`
	err := q.db.QueryRow(query, localID).Scan(
		&item.LocalID, &item.NodeUUID, &item.Blake3Hash, &item.LifecycleState, &isOffloadedInt,
		&item.CreatedAtUnix, &item.UpdatedAtUnix,
	)
	if err != nil {
		return nil, err
	}
	item.IsOffloaded = (isOffloadedInt == 1)
	return &item, nil
}
