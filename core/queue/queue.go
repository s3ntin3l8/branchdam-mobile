package queue

import (
	"database/sql"
	"fmt"
	"net/url"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

// queuePragmas are applied to EVERY connection the pool hands out, via the
// DSN's _pragma list. A PRAGMA issued after open would only apply to the
// one connection it ran on.
var queuePragmas = []string{
	"busy_timeout(5000)",
	"journal_mode(WAL)",
	"synchronous(NORMAL)",
}

// dsn builds the driver DSN for the queue.db at path: an absolute "file:"
// URI with the path percent-escaped, plus the _pragma list.
//
// The driver hands a "file:" DSN to SQLite as a URI, and SQLite reads '?'
// as the start of the parameters, '#' as a fragment and '%' as an escape --
// so an unescaped "file:"+path silently opened a DIFFERENT file for a path
// containing any of them, and a '?' in the path could inject further DSN
// parameters. url.URL escapes exactly those (and spaces). A Windows drive
// path becomes file:///C:/..., which SQLite accepts.
//
// This mirrors branchdam-agent's internal/queue.dsn, so the two clients open
// their queue databases the same way.
func dsn(path string) (string, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	p := filepath.ToSlash(abs)
	if !strings.HasPrefix(p, "/") {
		p = "/" + p
	}
	q := url.Values{}
	for _, pr := range queuePragmas {
		q.Add("_pragma", pr)
	}
	u := url.URL{Scheme: "file", Path: p, RawQuery: q.Encode()}
	return u.String(), nil
}

// Queue manages local offline buffering for uploads, events, and local media state.
type Queue struct {
	db *sql.DB
	mu sync.RWMutex
}

// Open initializes or connects to SQLite at dbPath and executes schema migrations.
func Open(dbPath string) (*Queue, error) {
	d, err := dsn(dbPath)
	if err != nil {
		return nil, fmt.Errorf("queue: build dsn for %s: %w", dbPath, err)
	}
	db, err := sql.Open("sqlite", d)
	if err != nil {
		return nil, fmt.Errorf("failed to open sqlite db: %w", err)
	}

	db.SetMaxOpenConns(1) // serialize writes safely for modernc sqlite

	if _, err := db.Exec(schemaSQL); err != nil {
		db.Close()
		return nil, fmt.Errorf("failed to execute queue schema: %w", err)
	}

	// Schema evolution: columns added after the first release are created
	// here on databases that predate them (CREATE TABLE IF NOT EXISTS
	// leaves an existing table untouched).
	for _, col := range addedColumns {
		if err := ensureColumn(db, col.table, col.name, col.ddl); err != nil {
			db.Close()
			return nil, err
		}
	}

	// A row is IN_PROGRESS only while a sync in this process holds it. Any
	// IN_PROGRESS row seen at open time was claimed by a session that
	// crashed or was killed, so both queues hand it back as PENDING (a
	// retry is not counted: the attempt never finished). Best-effort, like
	// the stale-claim reclaim the engine runs before every sync.
	_, _ = resetInProgress(db, "upload_queue", 0)
	_, _ = resetInProgress(db, "event_queue", 0)

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

// addedColumns lists the columns introduced after a table's first release,
// in the order they were added. Open adds any that an existing database
// lacks; each ddl must carry a DEFAULT so existing rows stay valid.
var addedColumns = []struct{ table, name, ddl string }{
	{"upload_queue", "source_path_hash", "TEXT NOT NULL DEFAULT ''"},
	// mtime_unix_nano is the file's modification time when it was hashed;
	// 0 on rows queued before it existed (only size is compared for those).
	{"upload_queue", "mtime_unix_nano", "INTEGER NOT NULL DEFAULT 0"},
	// size_bytes and modified_unix are the reclaim baseline: the original
	// media item's size and modification time (as the platform's media
	// store reports them) when its BLAKE3 was recorded. 0 on rows recorded
	// before they existed, or by a shell that supplied none -- such rows
	// are never reclaimed (see engine.ReasonNoLocalBaseline).
	{"local_media_state", "size_bytes", "INTEGER NOT NULL DEFAULT 0"},
	{"local_media_state", "modified_unix", "INTEGER NOT NULL DEFAULT 0"},
}

// ensureColumn adds column name to table unless it already exists.
func ensureColumn(db *sql.DB, table, name, ddl string) error {
	rows, err := db.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		return fmt.Errorf("query %s table_info: %w", table, err)
	}
	found := false
	for rows.Next() {
		var cid int
		var colName, colType string
		var notNull, pk int
		var dfltValue any
		if err := rows.Scan(&cid, &colName, &colType, &notNull, &dfltValue, &pk); err != nil {
			_ = rows.Close()
			return fmt.Errorf("scan %s table_info: %w", table, err)
		}
		if colName == name {
			found = true
			break
		}
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return fmt.Errorf("iterate %s table_info: %w", table, err)
	}
	_ = rows.Close()
	if found {
		return nil
	}
	if _, err := db.Exec(`ALTER TABLE ` + table + ` ADD COLUMN ` + name + ` ` + ddl); err != nil {
		return fmt.Errorf("add %s.%s column: %w", table, name, err)
	}
	return nil
}

// resetInProgress hands IN_PROGRESS rows of table ("upload_queue" or
// "event_queue") back to PENDING without counting a retry. With
// olderThanUnix 0 every IN_PROGRESS row is reset; otherwise only rows
// claimed (last_attempt_unix) before it. last_attempt_unix is cleared so the
// row is claimable at once: the attempt it records never finished, and the
// row had already waited out its backoff when it was claimed.
func resetInProgress(db *sql.DB, table string, olderThanUnix int64) (int64, error) {
	query := `UPDATE ` + table + ` SET status = 'PENDING', last_attempt_unix = 0, updated_at_unix = ?
	WHERE status = 'IN_PROGRESS'`
	args := []any{nowUnix()}
	if olderThanUnix > 0 {
		query += ` AND last_attempt_unix < ?`
		args = append(args, olderThanUnix)
	}
	res, err := db.Exec(query, args...)
	if err != nil {
		return 0, fmt.Errorf("reset %s IN_PROGRESS rows: %w", table, err)
	}
	return res.RowsAffected()
}

// releaseClaimed hands specific claimed rows of table back to PENDING
// without counting a retry (see resetInProgress). Only rows still
// IN_PROGRESS are touched, so a row the caller already completed or failed
// keeps its status.
func releaseClaimed(db *sql.DB, table string, ids []int64) error {
	if len(ids) == 0 {
		return nil
	}
	placeholders := make([]string, len(ids))
	args := make([]any, 0, len(ids)+1)
	args = append(args, nowUnix())
	for i, id := range ids {
		placeholders[i] = "?"
		args = append(args, id)
	}
	query := `UPDATE ` + table + ` SET status = 'PENDING', last_attempt_unix = 0, updated_at_unix = ?
	WHERE status = 'IN_PROGRESS' AND id IN (` + strings.Join(placeholders, ",") + `)`
	if _, err := db.Exec(query, args...); err != nil {
		return fmt.Errorf("release claimed %s rows: %w", table, err)
	}
	return nil
}

// ReleaseUploads returns claimed upload rows that were never attempted, or
// whose attempt was interrupted by cancellation, to PENDING without
// counting a retry.
func (q *Queue) ReleaseUploads(ids []int64) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	return releaseClaimed(q.db, "upload_queue", ids)
}

// ReleaseEvents is ReleaseUploads for the event queue.
func (q *Queue) ReleaseEvents(ids []int64) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	return releaseClaimed(q.db, "event_queue", ids)
}

// ReclaimStaleClaims returns IN_PROGRESS rows of both queues that were
// claimed more than maxAge ago to PENDING without counting a retry. Every
// sync path releases what it claimed before it returns, so such a row was
// orphaned (a panic, a failed release write, another process that died)
// and would otherwise stay stuck until the next Open. The engine runs this
// before each sync, so a long-lived process recovers without a restart.
// Returns how many upload and event rows were reclaimed.
func (q *Queue) ReclaimStaleClaims(maxAge time.Duration) (uploads, events int64, err error) {
	if maxAge <= 0 {
		return 0, 0, fmt.Errorf("ReclaimStaleClaims: maxAge must be positive")
	}
	q.mu.Lock()
	defer q.mu.Unlock()
	cutoff := time.Now().Add(-maxAge).Unix()
	if uploads, err = resetInProgress(q.db, "upload_queue", cutoff); err != nil {
		return 0, 0, err
	}
	if events, err = resetInProgress(q.db, "event_queue", cutoff); err != nil {
		return uploads, 0, err
	}
	return uploads, events, nil
}

// DefaultMaxRetryBackoffSecs caps the exponential retry backoff the claim
// queries apply (base << retry_count): one hour.
const DefaultMaxRetryBackoffSecs = 3600

// retryBackoffClause is the claim-query condition for a row that has been
// attempted before: it becomes eligible once last_attempt_unix is at least
// min(base << retry_count, cap) seconds in the past. The shift is clamped so
// it can never overflow. Placeholders, in order: now, base, cap.
const retryBackoffClause = `(last_attempt_unix = 0 OR last_attempt_unix <= ? - MIN(? << MIN(retry_count, 30), ?))`
