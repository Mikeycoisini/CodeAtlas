CREATE TABLE relation_corrections (
  id VARCHAR(36) PRIMARY KEY,
  snapshot_id VARCHAR(36) NOT NULL,
  generation_id VARCHAR(36) NOT NULL,
  relation_id VARCHAR(36) NOT NULL,
  verdict VARCHAR(20) NOT NULL,
  corrected_kind VARCHAR(40),
  corrected_target_text VARCHAR(1000),
  reason VARCHAR(2000) NOT NULL,
  source VARCHAR(1000),
  created_at VARCHAR(40) NOT NULL,
  FOREIGN KEY(snapshot_id) REFERENCES snapshots(id),
  FOREIGN KEY(generation_id) REFERENCES generations(id),
  FOREIGN KEY(relation_id) REFERENCES relations(id)
);
CREATE INDEX relation_corrections_relation ON relation_corrections(generation_id, relation_id, created_at);
