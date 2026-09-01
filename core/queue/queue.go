package queue

import (
	"database/sql"
	"fmt"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

// Queue manages local offline buffering for uploads, events, and local media state.
type Queue struct {
	db *sql.DB
	mu sync.RWMutex
}

// Open initializes or connects to SQLite at dbPath and executes schema migrations.
func Open(dbPath string) (*Queue, error) {
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=synchronous(NORMAL)", dbPath)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("failed to open sqlite db: %w", err)
	}

	db.SetMaxOpenConns(1) // serialize writes safely for modernc sqlite

	if _, err := db.Exec(schemaSQL); err != nil {
		db.Close()
		return nil, fmt.Errorf("failed to execute queue schema: %w", err)
	}

	// Reset any orphaned IN_PROGRESS items from a previous crashed/killed session back to PENDING
	now := time.Now().Unix()
	_, _ = db.Exec(`UPDATE upload_queue SET status = 'PENDING', updated_at_unix = ? WHERE status = 'IN_PROGRESS'`, now)

	return &Queue{db: db}, nil
}

// Close releases the underlying SQLite database handle.
func (q *Queue) Close() error {
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.db != nil {
		return q.db.Close()
	}
	return nil
}

// IsCancelRequested returns the current value of the upload cancel flag.
// The flag is intended to be checked between items; the engine reads it
// after every successful upload.
//
// Note: cancel state is now tracked in-process on the Engine
// (sync/atomic). The SQLite flag is retained for diagnostics; the
// engine's SyncUploads / SyncEvents capture+reset their own
// in-process flag at the start of each batch.
func (q *Queue) IsCancelRequested() (bool, error) {
	q.mu.RLock()
	defer q.mu.RUnlock()
	var v int
	if err := q.db.QueryRow(`SELECT cancel_requested FROM upload_queue LIMIT 1`).Scan(&v); err != nil {
		return false, fmt.Errorf("read upload cancel flag: %w", err)
	}
	return v == 1, nil
}

func nowUnix() int64 {
	return time.Now().Unix()
}
