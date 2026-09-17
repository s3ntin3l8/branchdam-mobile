package queue

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
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

	query := `
	INSERT INTO local_media_state (
		local_id, node_uuid, blake3_hash, lifecycle_state, is_offloaded,
		created_at_unix, updated_at_unix
	) VALUES (?, '', '', 'ARCHIVED', ?, ?, ?)
	ON CONFLICT(local_id) DO UPDATE SET
		is_offloaded = excluded.is_offloaded,
		updated_at_unix = excluded.updated_at_unix
	`
	_, err := q.db.Exec(query, localID, offloadedInt, now, now)
	return err
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

// UpdateLocalMediaNodeUUID updates local media state with the server node UUID.
func (q *Queue) UpdateLocalMediaNodeUUID(blake3Hash, nodeUUID string) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	query := `UPDATE local_media_state SET node_uuid = ?, updated_at_unix = ? WHERE blake3_hash = ?`
	_, err := q.db.Exec(query, nodeUUID, now, blake3Hash)
	return err
}

// GetMediaStatus returns the status of a media asset identified by localID or localPath.
// Returns one of: "OFFLOADED", "COMPLETED", "IN_PROGRESS", "PENDING", "FAILED", or "NOT_ENQUEUED".
func (q *Queue) GetMediaStatus(localID string) (string, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	if localID == "" {
		return "NOT_ENQUEUED", nil
	}

	// 1. Check local_media_state
	var nodeUUID string
	var isOffloadedInt int
	err := q.db.QueryRow(`SELECT node_uuid, is_offloaded FROM local_media_state WHERE local_id = ?`, localID).Scan(&nodeUUID, &isOffloadedInt)
	if err == nil {
		if isOffloadedInt == 1 {
			return "OFFLOADED", nil
		}
		if nodeUUID != "" {
			return "COMPLETED", nil
		}
	} else if !errors.Is(err, sql.ErrNoRows) {
		return "NOT_ENQUEUED", fmt.Errorf("query local media state failed: %w", err)
	}

	// 2. Check upload_queue using source_path_hash, local_path, or blake3_hash from local_media_state
	h := sha256.Sum256([]byte(localID))
	srcPathHash := hex.EncodeToString(h[:])

	var statusStr string
	query := `
	SELECT status FROM upload_queue
	WHERE source_path_hash = ?
	   OR local_path = ?
	   OR blake3_hash = (SELECT blake3_hash FROM local_media_state WHERE local_id = ?)
	ORDER BY id DESC LIMIT 1
	`
	err = q.db.QueryRow(query, srcPathHash, localID, localID).Scan(&statusStr)
	if err == nil {
		switch UploadStatus(statusStr) {
		case UploadCompleted:
			return "COMPLETED", nil
		case UploadInProgress:
			return "IN_PROGRESS", nil
		case UploadPending:
			return "PENDING", nil
		case UploadFailed:
			return "FAILED", nil
		}
	} else if !errors.Is(err, sql.ErrNoRows) {
		return "NOT_ENQUEUED", fmt.Errorf("query upload queue failed: %w", err)
	}

	return "NOT_ENQUEUED", nil
}

// GetAllMediaStatuses returns a map of localID/localPath/filename/srcPathHash -> status
// ("COMPLETED", "PENDING", "IN_PROGRESS", "FAILED", "OFFLOADED") for all items in local_media_state and upload_queue.
func (q *Queue) GetAllMediaStatuses() (map[string]string, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	result := make(map[string]string)

	// 1. Query upload_queue for all upload items
	rows, err := q.db.Query(`SELECT source_path_hash, local_path, target_filename, blake3_hash, status FROM upload_queue ORDER BY id ASC`)
	if err == nil {
		for rows.Next() {
			var srcHash, localPath, filename, blake3Hash, statusStr string
			if err := rows.Scan(&srcHash, &localPath, &filename, &blake3Hash, &statusStr); err == nil {
				st := "NOT_ENQUEUED"
				switch UploadStatus(statusStr) {
				case UploadCompleted:
					st = "COMPLETED"
				case UploadInProgress:
					st = "IN_PROGRESS"
				case UploadPending:
					st = "PENDING"
				case UploadFailed:
					st = "FAILED"
				}
				if srcHash != "" {
					result[srcHash] = st
				}
				if localPath != "" {
					result[localPath] = st
				}
				if filename != "" {
					result[filename] = st
				}
				if blake3Hash != "" {
					result[blake3Hash] = st
				}
			}
		}
		rows.Close()
	}

	// 2. Query local_media_state
	rowsState, err := q.db.Query(`SELECT local_id, node_uuid, blake3_hash, is_offloaded FROM local_media_state`)
	if err == nil {
		for rowsState.Next() {
			var localID, nodeUUID, blake3Hash string
			var isOffloadedInt int
			if err := rowsState.Scan(&localID, &nodeUUID, &blake3Hash, &isOffloadedInt); err == nil && localID != "" {
				h := sha256.Sum256([]byte(localID))
				srcHash := hex.EncodeToString(h[:])

				if isOffloadedInt == 1 {
					result[localID] = "OFFLOADED"
					result[srcHash] = "OFFLOADED"
				} else if nodeUUID != "" {
					result[localID] = "COMPLETED"
					result[srcHash] = "COMPLETED"
				} else {
					if st, exists := result[srcHash]; exists && st != "NOT_ENQUEUED" {
						result[localID] = st
					} else if st, exists := result[blake3Hash]; exists && st != "NOT_ENQUEUED" {
						result[localID] = st
					} else if st, exists := result[localID]; exists && st != "NOT_ENQUEUED" {
						result[localID] = st
					}
				}
			}
		}
		rowsState.Close()
	}

	return result, nil
}
