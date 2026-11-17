ALTER TABLE learning_evidence ADD COLUMN record_order BIGINT NOT NULL DEFAULT 0;
CREATE INDEX evidence_knowledge_order ON learning_evidence(knowledge_id,occurred_at_ms,created_at_ms,record_order);
