CREATE TABLE training_tasks (
  id VARCHAR(36) PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  status VARCHAR(20) NOT NULL,
  revision INT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  deleted_at VARCHAR(40),
  CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED'))
);
CREATE TABLE training_task_revisions (
  task_id VARCHAR(36) NOT NULL,
  revision INT NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  definition_digest VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  PRIMARY KEY(task_id,revision),
  FOREIGN KEY(task_id) REFERENCES training_tasks(id)
);
CREATE TABLE training_task_sources (
  task_id VARCHAR(36) NOT NULL,
  task_revision INT NOT NULL,
  source_id VARCHAR(36) NOT NULL,
  PRIMARY KEY(task_id,task_revision,source_id),
  FOREIGN KEY(task_id,task_revision) REFERENCES training_task_revisions(task_id,revision),
  FOREIGN KEY(source_id) REFERENCES evidence_sources(id)
);
CREATE TABLE training_attempts (
  id VARCHAR(36) PRIMARY KEY,
  task_id VARCHAR(36) NOT NULL,
  task_revision INT NOT NULL,
  definition_digest VARCHAR(64) NOT NULL,
  revision INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  answers_payload MEDIUMTEXT NOT NULL,
  results_payload MEDIUMTEXT,
  submission_digest VARCHAR(64),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  submitted_at VARCHAR(40),
  FOREIGN KEY(task_id,task_revision) REFERENCES training_task_revisions(task_id,revision),
  CHECK (status IN ('DRAFT','SUBMITTED','SKIPPED'))
);
CREATE TABLE training_attempt_events (
  id VARCHAR(36) PRIMARY KEY,
  attempt_id VARCHAR(36) NOT NULL,
  question_id VARCHAR(36) NOT NULL,
  kind VARCHAR(20) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  FOREIGN KEY(attempt_id) REFERENCES training_attempts(id),
  CHECK (kind IN ('HINT','REFERENCE'))
);
CREATE TABLE training_self_assessments (
  id VARCHAR(36) PRIMARY KEY,
  attempt_id VARCHAR(36) NOT NULL,
  question_id VARCHAR(36) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  FOREIGN KEY(attempt_id) REFERENCES training_attempts(id)
);
CREATE TABLE training_ai_requests (
  context_id VARCHAR(36) PRIMARY KEY,
  operation VARCHAR(40) NOT NULL,
  task_id VARCHAR(36),
  task_revision INT,
  attempt_id VARCHAR(36),
  question_id VARCHAR(36),
  created_at VARCHAR(40) NOT NULL,
  FOREIGN KEY(context_id) REFERENCES ai_contexts(id),
  FOREIGN KEY(task_id,task_revision) REFERENCES training_task_revisions(task_id,revision),
  FOREIGN KEY(attempt_id) REFERENCES training_attempts(id)
);
CREATE INDEX training_task_status_updated ON training_tasks(status,updated_at);
CREATE INDEX training_attempt_task_created ON training_attempts(task_id,created_at);
CREATE INDEX training_event_attempt ON training_attempt_events(attempt_id,created_at);
CREATE INDEX training_ai_attempt ON training_ai_requests(attempt_id,created_at);
