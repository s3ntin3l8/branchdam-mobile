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
    size_bytes          INTEGER NOT NULL DEFAULT 0,
    captured_at_unix    INTEGER NOT NULL DEFAULT 0,
    status              TEXT    NOT NULL DEFAULT 'PENDING',
    retry_count         INTEGER NOT NULL DEFAULT 0,
    last_attempt_unix   INTEGER NOT NULL DEFAULT 0,
    error_msg           TEXT    NOT NULL DEFAULT '',
    node_uuid           TEXT    NOT NULL DEFAULT '',
    created_at_unix     INTEGER NOT NULL,
    updated_at_unix     INTEGER NOT NULL
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
    updated_at_unix     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_local_media_node_uuid
ON local_media_state(node_uuid);
`
