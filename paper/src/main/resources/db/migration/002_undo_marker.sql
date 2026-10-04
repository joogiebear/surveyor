ALTER TABLE operations ADD COLUMN undone_by TEXT;

CREATE INDEX operations_actor ON operations (actor_type, actor_id, created_at);
