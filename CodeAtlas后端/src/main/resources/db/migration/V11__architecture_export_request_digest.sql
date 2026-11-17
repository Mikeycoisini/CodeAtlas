ALTER TABLE architecture_exports ADD COLUMN request_digest VARCHAR(64);
CREATE INDEX architecture_exports_request ON architecture_exports(snapshot_id, idempotency_key, request_digest);
