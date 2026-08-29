package queue

import (
	"fmt"
)

// EnqueueUpload inserts a new pending upload item into the queue.
func (q *Queue) EnqueueUpload(item *UploadItem) (int64, error) {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	item.CreatedAtUnix = now
	item.UpdatedAtUnix = now
	item.Status = UploadPending

	query := `
	INSERT INTO upload_queue (
		local_path, target_filename, target_dir, fast_hash, blake3_hash,
		camera_model, size_bytes, captured_at_unix, status, retry_count, last_attempt_unix,
		error_msg, node_uuid, created_at_unix, updated_at_unix
	) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`
	res, err := q.db.Exec(query,
		item.LocalPath, item.TargetFilename, item.TargetDir, item.FastHash, item.Blake3Hash,
		item.CameraModel, item.SizeBytes, item.CapturedAtUnix, string(item.Status), item.RetryCount, item.LastAttemptUnix,
		item.ErrorMsg, item.NodeUUID, item.CreatedAtUnix, item.UpdatedAtUnix,
	)
	if err != nil {
		return 0, fmt.Errorf("failed to enqueue upload: %w", err)
	}

	id, err := res.LastInsertId()
	if err != nil {
		return 0, err
	}
	item.ID = id
	return id, nil
}

// GetUploadItemByBlake3Hash returns the most recent pending or completed
// queue item with the given BLAKE3 hash, or nil if none exists.
// Used by EnqueueLocalCapture to gate duplicate enqueue.
func (q *Queue) GetUploadItemByBlake3Hash(hash string) (*UploadItem, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	query := `
	SELECT id, local_path, target_filename, target_dir, fast_hash, blake3_hash,
	       camera_model, size_bytes, captured_at_unix, status, retry_count, last_attempt_unix,
	       error_msg, node_uuid, created_at_unix, updated_at_unix
	FROM upload_queue
	WHERE blake3_hash = ?
	ORDER BY id DESC
	LIMIT 1
	`
	var item UploadItem
	var statusStr string
	err := q.db.QueryRow(query, hash).Scan(
		&item.ID, &item.LocalPath, &item.TargetFilename, &item.TargetDir, &item.FastHash, &item.Blake3Hash,
		&item.CameraModel, &item.SizeBytes, &item.CapturedAtUnix, &statusStr, &item.RetryCount, &item.LastAttemptUnix,
		&item.ErrorMsg, &item.NodeUUID, &item.CreatedAtUnix, &item.UpdatedAtUnix,
	)
	if err != nil {
		if err.Error() == "sql: no rows in result set" {
			return nil, nil
		}
		return nil, fmt.Errorf("failed to query upload item by blake3 hash: %w", err)
	}
	item.Status = UploadStatus(statusStr)
	return &item, nil
}

// ClaimPendingUploads retrieves pending upload items eligible for attempt, reclaiming stale in-progress items.
func (q *Queue) ClaimPendingUploads(limit int, retryBackoffSecs int64, maxRetries int) ([]*UploadItem, error) {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	cutoff := now - retryBackoffSecs

	query := `
	SELECT id, local_path, target_filename, target_dir, fast_hash, blake3_hash,
	       camera_model, size_bytes, captured_at_unix, status, retry_count, last_attempt_unix,
	       error_msg, node_uuid, created_at_unix, updated_at_unix
	FROM upload_queue
	WHERE (status = 'PENDING' OR (status = 'IN_PROGRESS' AND last_attempt_unix <= ?))
	  AND retry_count < ?
	  AND (last_attempt_unix = 0 OR last_attempt_unix <= ?)
	ORDER BY id ASC
	LIMIT ?
	`
	rows, err := q.db.Query(query, cutoff, maxRetries, cutoff, limit)
	if err != nil {
		return nil, fmt.Errorf("failed to claim pending uploads: %w", err)
	}
	defer rows.Close()

	var items []*UploadItem
	for rows.Next() {
		var item UploadItem
		var statusStr string
		err := rows.Scan(
			&item.ID, &item.LocalPath, &item.TargetFilename, &item.TargetDir, &item.FastHash, &item.Blake3Hash,
			&item.CameraModel, &item.SizeBytes, &item.CapturedAtUnix, &statusStr, &item.RetryCount, &item.LastAttemptUnix,
			&item.ErrorMsg, &item.NodeUUID, &item.CreatedAtUnix, &item.UpdatedAtUnix,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan upload row: %w", err)
		}
		item.Status = UploadStatus(statusStr)
		items = append(items, &item)
	}

	return items, nil
}

// MarkUploadInProgress flags an item as actively uploading and updates attempt timestamp.
func (q *Queue) MarkUploadInProgress(id int64) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	query := `UPDATE upload_queue SET status = 'IN_PROGRESS', last_attempt_unix = ?, updated_at_unix = ? WHERE id = ?`
	_, err := q.db.Exec(query, now, now, id)
	return err
}

// MarkUploadComplete marks the item as successfully uploaded with its assigned nodeUuid.
func (q *Queue) MarkUploadComplete(id int64, nodeUUID string) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	query := `UPDATE upload_queue SET status = 'COMPLETED', node_uuid = ?, error_msg = '', updated_at_unix = ? WHERE id = ?`
	_, err := q.db.Exec(query, nodeUUID, now, id)
	return err
}

// MarkUploadFailed records a failure, increments retry count, and resets status to PENDING or FAILED.
func (q *Queue) MarkUploadFailed(id int64, errMsg string, maxRetries int) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	query := `
	UPDATE upload_queue
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

// CountPendingUploads returns the total number of non-completed upload tasks.
func (q *Queue) CountPendingUploads() (int64, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	var count int64
	err := q.db.QueryRow(`SELECT COUNT(*) FROM upload_queue WHERE status IN ('PENDING', 'IN_PROGRESS')`).Scan(&count)
	return count, err
}
