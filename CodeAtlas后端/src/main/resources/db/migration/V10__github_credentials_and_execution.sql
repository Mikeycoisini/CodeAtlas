ALTER TABLE tasks ADD COLUMN credential_id VARCHAR(36);

CREATE TABLE github_credentials (
  id VARCHAR(36) PRIMARY KEY,
  label VARCHAR(120) NOT NULL,
  token_cipher MEDIUMTEXT NOT NULL,
  token_iv VARCHAR(64) NOT NULL,
  scopes VARCHAR(500),
  created_at VARCHAR(40) NOT NULL,
  last_used_at VARCHAR(40),
  revoked_at VARCHAR(40)
);

CREATE TABLE github_repo_bindings (
  project_id VARCHAR(36) PRIMARY KEY,
  credential_id VARCHAR(36) NOT NULL,
  repo_url VARCHAR(300) NOT NULL,
  ref VARCHAR(200),
  last_commit VARCHAR(64),
  last_checked_at VARCHAR(40),
  FOREIGN KEY (project_id) REFERENCES projects(id),
  FOREIGN KEY (credential_id) REFERENCES github_credentials(id)
);

CREATE TABLE execution_jobs (
  id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  snapshot_id VARCHAR(36),
  task_id VARCHAR(36),
  status VARCHAR(20) NOT NULL,
  command TEXT,
  exit_code INT,
  stdout MEDIUMTEXT,
  stderr MEDIUMTEXT,
  error_code VARCHAR(80),
  message TEXT,
  started_at VARCHAR(40),
  finished_at VARCHAR(40),
  created_at VARCHAR(40) NOT NULL,
  FOREIGN KEY (project_id) REFERENCES projects(id)
);
CREATE INDEX execution_jobs_project ON execution_jobs(project_id, created_at);
