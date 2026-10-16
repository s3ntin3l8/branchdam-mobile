package queue

import (
	"database/sql"
	"errors"
	"fmt"
)

// PendingImportState is the state of a pending_import row.
type PendingImportState string

const (
	// PendingImportPending: the item was found on the device and is
	// waiting for the user to confirm (or skip) its import.
	PendingImportPending PendingImportState = "PENDING"
	// PendingImportSuppressed: the user chose to skip the item; scans
	// must not offer it again.
	PendingImportSuppressed PendingImportState = "SUPPRESSED"
)

// PendingImport is a camera-roll item awaiting the user's import decision,
// or one the user skipped. It is persisted so the decision survives the
// app process being killed between discovery and confirmation.
type PendingImport struct {
	LocalID        string             `json:"localId"`
	LocalPath      string             `json:"localPath"`
	Filename       string             `json:"filename"`
	MimeType       string             `json:"mimeType,omitempty"`
	SizeBytes      int64              `json:"sizeBytes"`
	CapturedAtUnix int64              `json:"capturedAtUnix"`
	State          PendingImportState `json:"state"`
	CreatedAtUnix  int64              `json:"createdAtUnix"`
	UpdatedAtUnix  int64              `json:"updatedAtUnix"`
}

// ErrLocalIDRequired is returned by the pending-import API when localID is
// empty.
var ErrLocalIDRequired = errors.New("localID is required")

// StagePendingImport records p as awaiting the user's import decision.
// Re-staging a PENDING item refreshes its metadata. A SUPPRESSED item is
// left untouched (the user already skipped it), and staged reports false;
// staged is true when the item is now PENDING.
func (q *Queue) StagePendingImport(p PendingImport) (staged bool, err error) {
	if p.LocalID == "" {
		return false, ErrLocalIDRequired
	}
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	res, err := q.db.Exec(`
	INSERT INTO pending_import (
		local_id, local_path, filename, mime_type, size_bytes, captured_at_unix,
		state, created_at_unix, updated_at_unix
	) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
	ON CONFLICT(local_id) DO UPDATE SET
		local_path = excluded.local_path,
		filename = excluded.filename,
		mime_type = excluded.mime_type,
		size_bytes = excluded.size_bytes,
		captured_at_unix = excluded.captured_at_unix,
		updated_at_unix = excluded.updated_at_unix
	WHERE pending_import.state = 'PENDING'
	`, p.LocalID, p.LocalPath, p.Filename, p.MimeType, p.SizeBytes, p.CapturedAtUnix, now, now)
	if err != nil {
		return false, fmt.Errorf("stage pending import: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, fmt.Errorf("stage pending import: %w", err)
	}
	return n > 0, nil
}

// ListPendingImports returns PENDING items, oldest capture first. limit <= 0
// returns all of them.
func (q *Queue) ListPendingImports(limit int) ([]*PendingImport, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	if limit <= 0 {
		limit = -1 // SQLite: no limit
	}
	rows, err := q.db.Query(`
	SELECT local_id, local_path, filename, mime_type, size_bytes, captured_at_unix,
	       state, created_at_unix, updated_at_unix
	FROM pending_import
	WHERE state = 'PENDING'
	ORDER BY captured_at_unix ASC, local_id ASC
	LIMIT ?`, limit)
	if err != nil {
		return nil, fmt.Errorf("list pending imports: %w", err)
	}
	defer rows.Close()

	out := []*PendingImport{}
	for rows.Next() {
		var p PendingImport
		var state string
		if err := rows.Scan(&p.LocalID, &p.LocalPath, &p.Filename, &p.MimeType, &p.SizeBytes,
			&p.CapturedAtUnix, &state, &p.CreatedAtUnix, &p.UpdatedAtUnix); err != nil {
			return nil, fmt.Errorf("scan pending import: %w", err)
		}
		p.State = PendingImportState(state)
		out = append(out, &p)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate pending imports: %w", err)
	}
	return out, nil
}

// GetPendingImport returns the row for localID in either state, or
// sql.ErrNoRows when there is none.
func (q *Queue) GetPendingImport(localID string) (*PendingImport, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	var p PendingImport
	var state string
	err := q.db.QueryRow(`
	SELECT local_id, local_path, filename, mime_type, size_bytes, captured_at_unix,
	       state, created_at_unix, updated_at_unix
	FROM pending_import WHERE local_id = ?`, localID).Scan(
		&p.LocalID, &p.LocalPath, &p.Filename, &p.MimeType, &p.SizeBytes,
		&p.CapturedAtUnix, &state, &p.CreatedAtUnix, &p.UpdatedAtUnix)
	if err != nil {
		return nil, err
	}
	p.State = PendingImportState(state)
	return &p, nil
}

// DeletePendingImport removes the row for localID in either state: after
// the item was imported, or to lift a suppression. deleted is false when
// there was no row.
func (q *Queue) DeletePendingImport(localID string) (deleted bool, err error) {
	if localID == "" {
		return false, ErrLocalIDRequired
	}
	q.mu.Lock()
	defer q.mu.Unlock()

	res, err := q.db.Exec(`DELETE FROM pending_import WHERE local_id = ?`, localID)
	if err != nil {
		return false, fmt.Errorf("delete pending import: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, fmt.Errorf("delete pending import: %w", err)
	}
	return n > 0, nil
}

// SuppressPendingImport marks localID as skipped by the user. It works
// whether or not the item was staged first, keeping any metadata a staged
// row already has.
func (q *Queue) SuppressPendingImport(localID string) error {
	if localID == "" {
		return ErrLocalIDRequired
	}
	q.mu.Lock()
	defer q.mu.Unlock()

	now := nowUnix()
	_, err := q.db.Exec(`
	INSERT INTO pending_import (local_id, state, created_at_unix, updated_at_unix)
	VALUES (?, 'SUPPRESSED', ?, ?)
	ON CONFLICT(local_id) DO UPDATE SET
		state = 'SUPPRESSED',
		updated_at_unix = excluded.updated_at_unix
	`, localID, now, now)
	if err != nil {
		return fmt.Errorf("suppress pending import: %w", err)
	}
	return nil
}

// IsImportSuppressed reports whether the user skipped localID.
func (q *Queue) IsImportSuppressed(localID string) (bool, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()

	var state string
	err := q.db.QueryRow(`SELECT state FROM pending_import WHERE local_id = ?`, localID).Scan(&state)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return false, nil
		}
		return false, fmt.Errorf("query pending import: %w", err)
	}
	return PendingImportState(state) == PendingImportSuppressed, nil
}
