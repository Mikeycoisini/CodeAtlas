CREATE TABLE architecture_projections (
  id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  snapshot_id VARCHAR(36) NOT NULL,
  generation_id VARCHAR(36) NOT NULL,
  analyzer_version VARCHAR(40) NOT NULL,
  digest VARCHAR(64) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  FOREIGN KEY (project_id) REFERENCES projects(id),
  FOREIGN KEY (snapshot_id) REFERENCES snapshots(id),
  FOREIGN KEY (generation_id) REFERENCES generations(id)
);
CREATE INDEX architecture_projections_lookup ON architecture_projections(snapshot_id, generation_id, analyzer_version);

CREATE TABLE architecture_exports (
  id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  snapshot_id VARCHAR(36) NOT NULL,
  generation_id VARCHAR(36) NOT NULL,
  projection_id VARCHAR(36) NOT NULL,
  projection_digest VARCHAR(64) NOT NULL,
  format VARCHAR(24) NOT NULL,
  scope VARCHAR(40) NOT NULL,
  theme VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  error_code VARCHAR(80),
  message TEXT,
  artifact_path VARCHAR(1024),
  artifact_name VARCHAR(255),
  artifact_size BIGINT,
  artifact_sha256 VARCHAR(64),
  idempotency_key VARCHAR(100),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  FOREIGN KEY (snapshot_id) REFERENCES snapshots(id),
  FOREIGN KEY (generation_id) REFERENCES generations(id)
);
CREATE INDEX architecture_exports_lookup ON architecture_exports(snapshot_id, generation_id, idempotency_key);
