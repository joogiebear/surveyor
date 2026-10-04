CREATE TABLE regions (
    id INTEGER PRIMARY KEY,
    world TEXT NOT NULL,
    name TEXT NOT NULL,
    min_x INTEGER NOT NULL,
    min_y INTEGER NOT NULL,
    min_z INTEGER NOT NULL,
    max_x INTEGER NOT NULL,
    max_y INTEGER NOT NULL,
    max_z INTEGER NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    UNIQUE (world, name)
);

CREATE TABLE region_members (
    region_id INTEGER NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    subject_type TEXT NOT NULL,
    subject_id TEXT NOT NULL,
    role TEXT NOT NULL,
    PRIMARY KEY (region_id, subject_type, subject_id)
);

CREATE TABLE region_flags (
    region_id INTEGER NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    flag TEXT NOT NULL,
    value TEXT NOT NULL,
    audience TEXT,
    PRIMARY KEY (region_id, flag)
);

CREATE TABLE agent_tokens (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    secret_hash TEXT NOT NULL,
    scope_json TEXT NOT NULL,
    created_by TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER,
    revoked_at INTEGER
);

CREATE TABLE operations (
    id TEXT PRIMARY KEY,
    actor_type TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    world TEXT,
    status TEXT NOT NULL,
    params_json TEXT,
    reason TEXT,
    blocks_changed INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    finished_at INTEGER
);

CREATE TABLE undo_journal (
    operation_id TEXT NOT NULL REFERENCES operations(id),
    chunk_x INTEGER NOT NULL,
    chunk_z INTEGER NOT NULL,
    seq INTEGER NOT NULL,
    data BLOB NOT NULL,
    PRIMARY KEY (operation_id, chunk_x, chunk_z, seq)
);

CREATE TABLE audit_log (
    id INTEGER PRIMARY KEY,
    at INTEGER NOT NULL,
    actor_type TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT,
    operation_id TEXT,
    result TEXT NOT NULL
);
