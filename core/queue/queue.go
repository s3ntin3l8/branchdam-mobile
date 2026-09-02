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

// DB returns the underlying *sql.DB. TEST-ONLY — not for production
// use. Exposed so root-package integration tests (branchdam_f_tests)
// can inject SQL-level failures (e.g. DROP TABLE, SQLite triggers)
// to verify error-mapping in the FFI surface. The method lives here
// rather than in a _test.go file because the callers are in a
// different package (branchdam), which cannot access unexported
// methods defined in queue's own _test.go files.
func (q *Queue) DB() *sql.DB {
	return q.db
}

func nowUnix() int64 {
	return time.Now().Unix()
}
