ALTER TABLE operations ADD COLUMN blocks_planned INTEGER NOT NULL DEFAULT 0;

ALTER TABLE operations ADD COLUMN skipped_protected INTEGER NOT NULL DEFAULT 0;

ALTER TABLE operations ADD COLUMN skipped_out_of_scope INTEGER NOT NULL DEFAULT 0;

ALTER TABLE operations ADD COLUMN regions_affected TEXT;

ALTER TABLE audit_log ADD COLUMN reason TEXT;

ALTER TABLE agent_tokens ADD COLUMN secret_prefix TEXT NOT NULL DEFAULT '';

CREATE TABLE idempotency (
    token_id INTEGER NOT NULL,
    key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    response_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (token_id, key)
);

CREATE INDEX audit_actor ON audit_log (actor_type, actor_id, at);

CREATE INDEX audit_operation ON audit_log (operation_id);
