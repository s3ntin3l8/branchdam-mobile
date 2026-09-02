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

// DB exposes the underlying *sql.DB. Exposed for branchdam FFI
// tests that need to inject SQL-level failures (e.g. DROP TABLE
// to force a SetMediaOffloaded error). Not part of the public API
// for production callers.
func (q *Queue) DB() *sql.DB {
	return q.db
}

func nowUnix() int64 {
	return time.Now().Unix()
}
