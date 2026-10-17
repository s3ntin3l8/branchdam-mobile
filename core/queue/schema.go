package queue

const schemaSQL = `
CREATE TABLE IF NOT EXISTS upload_queue (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    local_path          TEXT    NOT NULL,
    target_filename     TEXT    NOT NULL,
    target_dir          TEXT    NOT NULL DEFAULT '',
    fast_hash           TEXT    NOT NULL DEFAULT '',
    blake3_hash         TEXT    NOT NULL DEFAULT '',
    camera_model        TEXT    NOT NULL DEFAULT '',
    source_path_hash    TEXT    NOT NULL DEFAULT '',
    size_bytes          INTEGER NOT NULL DEFAULT 0,
    captured_at_unix    INTEGER NOT NULL DEFAULT 0,
    status              TEXT    NOT NULL DEFAULT 'PENDING',
    retry_count         INTEGER NOT NULL DEFAULT 0,
    last_attempt_unix   INTEGER NOT NULL DEFAULT 0,
    error_msg           TEXT    NOT NULL DEFAULT '',
    node_uuid           TEXT    NOT NULL DEFAULT '',
    created_at_unix     INTEGER NOT NULL,
    updated_at_unix     INTEGER NOT NULL,
    mtime_unix_nano     INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_upload_queue_status_retry
ON upload_queue(status, last_attempt_unix);

CREATE TABLE IF NOT EXISTS event_queue (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    event_uuid          TEXT    NOT NULL UNIQUE,
    event_type          TEXT    NOT NULL,
    payload_json        TEXT    NOT NULL,
    status              TEXT    NOT NULL DEFAULT 'PENDING',
    retry_count         INTEGER NOT NULL DEFAULT 0,
    last_attempt_unix   INTEGER NOT NULL DEFAULT 0,
    error_msg           TEXT    NOT NULL DEFAULT '',
    created_at_unix     INTEGER NOT NULL,
    updated_at_unix     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_event_queue_status_retry
ON event_queue(status, last_attempt_unix);

CREATE TABLE IF NOT EXISTS local_media_state (
    local_id            TEXT    PRIMARY KEY,
    node_uuid           TEXT    NOT NULL DEFAULT '',
    blake3_hash         TEXT    NOT NULL DEFAULT '',
    lifecycle_state     TEXT    NOT NULL DEFAULT 'ACTIVE',
    is_offloaded        INTEGER NOT NULL DEFAULT 0,
    created_at_unix     INTEGER NOT NULL,
    updated_at_unix     INTEGER NOT NULL,
    size_bytes          INTEGER NOT NULL DEFAULT 0,
    modified_unix       INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_local_media_node_uuid
ON local_media_state(node_uuid);

-- pending_import holds camera-roll items the user has not yet confirmed
-- for import (state PENDING) and items the user chose to skip (state
-- SUPPRESSED), so neither is lost when the app process dies.
CREATE TABLE IF NOT EXISTS pending_import (
    local_id            TEXT    PRIMARY KEY,
    local_path          TEXT    NOT NULL DEFAULT '',
    filename            TEXT    NOT NULL DEFAULT '',
    mime_type           TEXT    NOT NULL DEFAULT '',
    size_bytes          INTEGER NOT NULL DEFAULT 0,
    captured_at_unix    INTEGER NOT NULL DEFAULT 0,
    state               TEXT    NOT NULL DEFAULT 'PENDING',
    created_at_unix     INTEGER NOT NULL,
    updated_at_unix     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pending_import_state
ON pending_import(state, captured_at_unix);

-- lineage_review holds the user's decision on a lineage pair the device
-- proposed below confidence 1.00. Such a pair is never sent on its own; it
-- waits in the review screen until the user confirms it (CONFIRMED, and
-- event_uuid names the EVENT_EDGE_ATTACHED that carries it) or rejects it
-- (REJECTED, so it is never proposed again, across restarts).
CREATE TABLE IF NOT EXISTS lineage_review (
    parent_local_id     TEXT    NOT NULL,
    child_local_id      TEXT    NOT NULL,
    decision            TEXT    NOT NULL,
    relationship        TEXT    NOT NULL DEFAULT '',
    resolver            TEXT    NOT NULL DEFAULT '',
    confidence          REAL    NOT NULL DEFAULT 0,
    event_uuid          TEXT    NOT NULL DEFAULT '',
    created_at_unix     INTEGER NOT NULL,
    updated_at_unix     INTEGER NOT NULL,
    PRIMARY KEY (parent_local_id, child_local_id)
);
`
