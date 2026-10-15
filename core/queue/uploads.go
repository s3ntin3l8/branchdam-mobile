package queue

import (
	"database/sql"
	"fmt"
)

// EnqueueUpload inserts a new pending upload item into the queue.
func (q *Queue) EnqueueUpload(item *UploadItem) (int64, error) {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	item.CreatedAtUnix = now
	item.UpdatedAtUnix = now
	if item.Status == "" {
		item.Status = UploadPending
	}

	query := `
	INSERT INTO upload_queue (
		local_path, target_filename, target_dir, fast_hash, blake3_hash,
		camera_model, source_path_hash, size_bytes, captured_at_unix, status, retry_count, last_attempt_unix,
		error_msg, node_uuid, created_at_unix, updated_at_unix, mtime_unix_nano
	) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`
	res, err := q.db.Exec(query,
		item.LocalPath, item.TargetFilename, item.TargetDir, item.FastHash, item.Blake3Hash,
		item.CameraModel, item.SourcePathHash, item.SizeBytes, item.CapturedAtUnix, string(item.Status), item.RetryCount, item.LastAttemptUnix,
		item.ErrorMsg, item.NodeUUID, item.CreatedAtUnix, item.UpdatedAtUnix, item.ModTimeUnixNano,
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

// GetUploadItemByBlake3Hash returns the most recent active (PENDING, IN_PROGRESS, or COMPLETED)
// queue item with the given BLAKE3 hash, or nil if none exists.
// FAILED items are excluded so that exhausted-retry content can be re-enqueued.
// Used by EnqueueLocalCapture to gate duplicate enqueue.
func (q *Queue) GetUploadItemByBlake3Hash(hash string) (*UploadItem, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	query := `SELECT ` + uploadColumns + `
	FROM upload_queue
	WHERE blake3_hash = ?
	  AND status != 'FAILED'
	ORDER BY id DESC
	LIMIT 1
	`
	item, err := scanUploadItem(q.db.QueryRow(query, hash))
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, fmt.Errorf("failed to query upload item by blake3 hash: %w", err)
	}
	return item, nil
}

// GetUploadItem returns one upload row by its id.
func (q *Queue) GetUploadItem(id int64) (*UploadItem, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()
	return scanUploadItem(q.db.QueryRow(`SELECT `+uploadColumns+` FROM upload_queue WHERE id = ?`, id))
}

// uploadColumns is the column list scanUploadItem reads, in order.
const uploadColumns = `id, local_path, target_filename, target_dir, fast_hash, blake3_hash,
	       camera_model, source_path_hash, size_bytes, captured_at_unix, status, retry_count, last_attempt_unix,
	       error_msg, node_uuid, created_at_unix, updated_at_unix, mtime_unix_nano`

func scanUploadItem(row interface{ Scan(...any) error }) (*UploadItem, error) {
	var item UploadItem
	var statusStr string
	err := row.Scan(
		&item.ID, &item.LocalPath, &item.TargetFilename, &item.TargetDir, &item.FastHash, &item.Blake3Hash,
		&item.CameraModel, &item.SourcePathHash, &item.SizeBytes, &item.CapturedAtUnix, &statusStr, &item.RetryCount, &item.LastAttemptUnix,
		&item.ErrorMsg, &item.NodeUUID, &item.CreatedAtUnix, &item.UpdatedAtUnix, &item.ModTimeUnixNano,
	)
	if err != nil {
		return nil, err
	}
	item.Status = UploadStatus(statusStr)
	return &item, nil
}

// ClaimPendingUploads atomically claims a batch of pending upload items.
// Atomic via a single UPDATE...RETURNING statement so two concurrent
// goroutines (or processes) cannot double-claim the same id.
//
// Only PENDING items with fewer than maxRetries failed attempts are
// claimed. An item that was attempted before waits out an exponential
// backoff first: retryBackoffSecs << retry_count seconds after its last
// attempt, capped at DefaultMaxRetryBackoffSecs (so a never-failed item
// that was deferred waits retryBackoffSecs). Stale IN_PROGRESS items are
// handed back separately, by Open's startup recovery and by
// ReclaimStaleClaims, not here.
func (q *Queue) ClaimPendingUploads(limit int, retryBackoffSecs int64, maxRetries int) ([]*UploadItem, error) {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	if retryBackoffSecs < 0 {
		retryBackoffSecs = 0
	}

	query := `
	UPDATE upload_queue
	SET status = 'IN_PROGRESS',
	    last_attempt_unix = ?,
	    updated_at_unix    = ?
	WHERE id IN (
	  SELECT id FROM upload_queue
	  WHERE status = 'PENDING'
	    AND retry_count < ?
	    AND ` + retryBackoffClause + `
	  ORDER BY id ASC
	  LIMIT ?
	)
	RETURNING ` + uploadColumns + `
	`
	rows, err := q.db.Query(query, now, now, maxRetries, now, retryBackoffSecs, int64(DefaultMaxRetryBackoffSecs), limit)
	if err != nil {
		return nil, fmt.Errorf("failed to claim pending uploads: %w", err)
	}
	defer rows.Close()

	var items []*UploadItem
	for rows.Next() {
		item, err := scanUploadItem(rows)
		if err != nil {
			return nil, fmt.Errorf("failed to scan upload row: %w", err)
		}
		items = append(items, item)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("failed to iterate claimed uploads: %w", err)
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

// ResetFailedUploads resets all FAILED upload items back to PENDING with retry_count = 0.
func (q *Queue) ResetFailedUploads() (int64, error) {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	res, err := q.db.Exec(`UPDATE upload_queue SET status = 'PENDING', retry_count = 0, last_attempt_unix = 0, error_msg = '', updated_at_unix = ? WHERE status = 'FAILED'`, now)
	if err != nil {
		return 0, fmt.Errorf("failed to reset failed uploads: %w", err)
	}
	return res.RowsAffected()
}

// ResetUploadByBlake3Hash resets a FAILED upload item matching blake3Hash back to PENDING.
// Updates localPath, sizeBytes and the file's modification time so that
// re-enqueueing a moved/recovered asset targets the new path and the
// pre-upload re-stat compares against the file that was just hashed.
func (q *Queue) ResetUploadByBlake3Hash(hash string, localPath string, sizeBytes int64, mtimeUnixNano int64) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	query := `UPDATE upload_queue SET status = 'PENDING', retry_count = 0, last_attempt_unix = 0, error_msg = '', local_path = ?, size_bytes = ?, mtime_unix_nano = ?, updated_at_unix = ? WHERE blake3_hash = ? AND status = 'FAILED'`
	_, err := q.db.Exec(query, localPath, sizeBytes, mtimeUnixNano, now, hash)
	return err
}

// UpdateUploadContent records fresh hashes, size and modification time for
// a queued upload whose file changed after it was enqueued, so it is sent
// with hashes that match its current bytes. Local media rows that recorded
// the old hash and are not yet linked to a server node (and not offloaded)
// move to the new hash with it, so the upload's node UUID still reaches
// them once it completes.
func (q *Queue) UpdateUploadContent(id int64, fastHash, blake3Hash string, sizeBytes, mtimeUnixNano int64) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	tx, err := q.db.Begin()
	if err != nil {
		return fmt.Errorf("begin update upload content: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	var oldHash string
	if err := tx.QueryRow(`SELECT blake3_hash FROM upload_queue WHERE id = ?`, id).Scan(&oldHash); err != nil {
		return fmt.Errorf("read upload %d: %w", id, err)
	}
	now := nowUnix()
	if _, err := tx.Exec(`UPDATE upload_queue SET fast_hash = ?, blake3_hash = ?, size_bytes = ?, mtime_unix_nano = ?, updated_at_unix = ? WHERE id = ?`,
		fastHash, blake3Hash, sizeBytes, mtimeUnixNano, now, id); err != nil {
		return fmt.Errorf("update upload %d content: %w", id, err)
	}
	if oldHash != "" && oldHash != blake3Hash {
		if _, err := tx.Exec(`UPDATE local_media_state SET blake3_hash = ?, updated_at_unix = ?
			WHERE blake3_hash = ? AND node_uuid = '' AND is_offloaded = 0`, blake3Hash, now, oldHash); err != nil {
			return fmt.Errorf("move local media to new hash: %w", err)
		}
	}
	return tx.Commit()
}
