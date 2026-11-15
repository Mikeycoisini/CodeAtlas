ALTER TABLE mutation_requests ADD response_payload MEDIUMTEXT;
CREATE TABLE review_states (
  card_id VARCHAR(36) PRIMARY KEY,
  epoch INT NOT NULL,
  revision INT NOT NULL,
  content_digest VARCHAR(64) NOT NULL,
  state_json MEDIUMTEXT NOT NULL,
  algorithm_id VARCHAR(80) NOT NULL,
  config_hash VARCHAR(64) NOT NULL,
  due_at_ms BIGINT NOT NULL,
  suspended BOOLEAN NOT NULL,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  FOREIGN KEY(card_id) REFERENCES learning_records(id)
);
CREATE INDEX review_state_due ON review_states(suspended,due_at_ms,card_id);
CREATE TABLE review_attempts (
  id VARCHAR(36) PRIMARY KEY,
  card_id VARCHAR(36) NOT NULL,
  epoch INT NOT NULL,
  state_revision INT NOT NULL,
  revision INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  card_payload MEDIUMTEXT NOT NULL,
  content_digest VARCHAR(64) NOT NULL,
  recalled_answer TEXT NOT NULL,
  reflection TEXT NOT NULL,
  revealed_at_ms BIGINT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  FOREIGN KEY(card_id) REFERENCES learning_records(id),
  CHECK(status IN ('DRAFT','REVEALED','COMMITTED','SKIPPED'))
);
CREATE INDEX review_attempt_card ON review_attempts(card_id,created_at_ms,id);
CREATE TABLE review_events (
  id VARCHAR(36) PRIMARY KEY,
  card_id VARCHAR(36) NOT NULL,
  attempt_id VARCHAR(36) UNIQUE,
  kind VARCHAR(20) NOT NULL,
  epoch INT NOT NULL,
  state_revision INT NOT NULL,
  occurred_at_ms BIGINT NOT NULL,
  algorithm_id VARCHAR(80) NOT NULL,
  config_hash VARCHAR(64) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  FOREIGN KEY(card_id) REFERENCES learning_records(id),
  FOREIGN KEY(attempt_id) REFERENCES review_attempts(id),
  UNIQUE(card_id,epoch,state_revision)
);
CREATE INDEX review_event_history ON review_events(card_id,occurred_at_ms,id);
CREATE TABLE learning_evidence (
  id VARCHAR(36) PRIMARY KEY,
  knowledge_id VARCHAR(36) NOT NULL,
  knowledge_revision INT NOT NULL,
  source_type VARCHAR(40) NOT NULL,
  source_key VARCHAR(100) NOT NULL,
  occurred_at_ms BIGINT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  FOREIGN KEY(knowledge_id) REFERENCES learning_records(id),
  UNIQUE(knowledge_id,source_type,source_key)
);
CREATE INDEX evidence_knowledge_time ON learning_evidence(knowledge_id,occurred_at_ms,id);
CREATE TABLE evidence_retractions (
  id VARCHAR(36) PRIMARY KEY,
  evidence_id VARCHAR(36) NOT NULL UNIQUE,
  reason TEXT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  FOREIGN KEY(evidence_id) REFERENCES learning_evidence(id)
);
CREATE TABLE mastery_assessments (
  id VARCHAR(36) PRIMARY KEY,
  knowledge_id VARCHAR(36) NOT NULL,
  knowledge_revision INT NOT NULL,
  rule_version VARCHAR(40) NOT NULL,
  basis_digest VARCHAR(64) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  FOREIGN KEY(knowledge_id) REFERENCES learning_records(id)
);
CREATE TABLE knowledge_progress (
  knowledge_id VARCHAR(36) PRIMARY KEY,
  assessment_id VARCHAR(36) NOT NULL,
  revision INT NOT NULL,
  FOREIGN KEY(knowledge_id) REFERENCES learning_records(id),
  FOREIGN KEY(assessment_id) REFERENCES mastery_assessments(id)
);
CREATE TABLE guide_runs (
  id VARCHAR(36) PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  status VARCHAR(20) NOT NULL,
  revision INT NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  CHECK(status IN ('ACTIVE','PAUSED','EXITED','COMPLETED'))
);
CREATE TABLE guide_events (
  id VARCHAR(36) PRIMARY KEY,
  guide_id VARCHAR(36) NOT NULL,
  revision INT NOT NULL,
  kind VARCHAR(40) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  FOREIGN KEY(guide_id) REFERENCES guide_runs(id),
  UNIQUE(guide_id,revision)
);
CREATE TABLE guide_ai_requests (
  context_id VARCHAR(36) PRIMARY KEY,
  guide_id VARCHAR(36) NOT NULL,
  guide_revision INT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  FOREIGN KEY(context_id) REFERENCES ai_contexts(id),
  FOREIGN KEY(guide_id) REFERENCES guide_runs(id)
);
