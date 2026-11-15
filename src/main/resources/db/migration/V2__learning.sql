CREATE TABLE evidence_sources (
  id VARCHAR(36) PRIMARY KEY,
  source_hash VARCHAR(64) NOT NULL UNIQUE,
  project_id VARCHAR(36) NOT NULL,
  snapshot_id VARCHAR(36) NOT NULL,
  file_id VARCHAR(36) NOT NULL,
  generation_id VARCHAR(36),
  symbol_id VARCHAR(36),
  payload MEDIUMTEXT NOT NULL,
  FOREIGN KEY(project_id) REFERENCES projects(id),
  FOREIGN KEY(snapshot_id,file_id) REFERENCES snapshot_files(snapshot_id,id),
  FOREIGN KEY(generation_id,symbol_id) REFERENCES symbols(generation_id,id)
);
CREATE TABLE learning_records (
  id VARCHAR(36) PRIMARY KEY,
  kind VARCHAR(20) NOT NULL,
  title VARCHAR(500) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  knowledge_id VARCHAR(36),
  generation_id VARCHAR(36),
  revision INT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  deleted_at VARCHAR(40),
  FOREIGN KEY(knowledge_id) REFERENCES learning_records(id),
  CHECK (kind IN ('DOCUMENT','KNOWLEDGE','CARD'))
);
CREATE TABLE learning_sources (
  record_id VARCHAR(36) NOT NULL,
  source_id VARCHAR(36) NOT NULL,
  PRIMARY KEY(record_id,source_id),
  FOREIGN KEY(record_id) REFERENCES learning_records(id),
  FOREIGN KEY(source_id) REFERENCES evidence_sources(id)
);
CREATE TABLE mutation_requests (
  idempotency_key VARCHAR(100) PRIMARY KEY,
  operation VARCHAR(40) NOT NULL,
  body_hash VARCHAR(64) NOT NULL,
  result_id VARCHAR(36) NOT NULL,
  created_at VARCHAR(40) NOT NULL
);
CREATE TABLE ai_contexts (
  id VARCHAR(36) PRIMARY KEY,
  context_json MEDIUMTEXT NOT NULL,
  context_digest VARCHAR(64) NOT NULL,
  provider_fingerprint VARCHAR(64) NOT NULL,
  provider_json TEXT NOT NULL,
  parent_generation_id VARCHAR(36),
  expires_at VARCHAR(40) NOT NULL,
  created_at VARCHAR(40) NOT NULL
);
CREATE TABLE ai_context_sources (
  context_id VARCHAR(36) NOT NULL,
  source_id VARCHAR(36) NOT NULL,
  PRIMARY KEY(context_id,source_id),
  FOREIGN KEY(context_id) REFERENCES ai_contexts(id),
  FOREIGN KEY(source_id) REFERENCES evidence_sources(id)
);
CREATE TABLE ai_consents (
  id VARCHAR(36) PRIMARY KEY,
  context_id VARCHAR(36) NOT NULL UNIQUE,
  decision VARCHAR(10) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  FOREIGN KEY(context_id) REFERENCES ai_contexts(id),
  CHECK (decision IN ('ALLOW','DENY'))
);
CREATE TABLE ai_generations (
  id VARCHAR(36) PRIMARY KEY,
  context_id VARCHAR(36) NOT NULL,
  consent_id VARCHAR(36) NOT NULL UNIQUE,
  idempotency_key VARCHAR(100) NOT NULL UNIQUE,
  body_hash VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  result_json MEDIUMTEXT,
  error_code VARCHAR(80),
  message TEXT,
  outbound_started_at VARCHAR(40),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  FOREIGN KEY(context_id) REFERENCES ai_contexts(id),
  FOREIGN KEY(consent_id) REFERENCES ai_consents(id)
);
CREATE INDEX learning_kind_updated ON learning_records(kind,updated_at);
