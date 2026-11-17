CREATE TABLE projects (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  source_path VARCHAR(2048) NOT NULL,
  path_hash VARCHAR(64) NOT NULL UNIQUE,
  created_at VARCHAR(40) NOT NULL
);
CREATE TABLE snapshots (
  id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  manifest_hash VARCHAR(64) NOT NULL,
  identity_hash VARCHAR(64) NOT NULL,
  source_kind VARCHAR(20) NOT NULL,
  commit_hash VARCHAR(64),
  git_status TEXT,
  storage_key VARCHAR(100) NOT NULL,
  policy_version VARCHAR(40) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  published_generation VARCHAR(36),
  UNIQUE (project_id, identity_hash),
  FOREIGN KEY (project_id) REFERENCES projects(id)
);
CREATE TABLE snapshot_files (
  snapshot_id VARCHAR(36) NOT NULL,
  id VARCHAR(36) NOT NULL,
  path VARCHAR(2048) NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  byte_size BIGINT NOT NULL,
  PRIMARY KEY(snapshot_id, id),
  FOREIGN KEY(snapshot_id) REFERENCES snapshots(id)
);
CREATE TABLE tasks (
  id VARCHAR(36) PRIMARY KEY,
  kind VARCHAR(16) NOT NULL,
  source_path VARCHAR(2048),
  snapshot_id VARCHAR(36),
  generation_id VARCHAR(36),
  status VARCHAR(24) NOT NULL,
  progress INT NOT NULL DEFAULT 0,
  total INT NOT NULL DEFAULT 0,
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  error_code VARCHAR(80),
  message TEXT,
  analyzer_version VARCHAR(40) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  FOREIGN KEY(snapshot_id) REFERENCES snapshots(id)
);
CREATE TABLE generations (
  id VARCHAR(36) PRIMARY KEY,
  snapshot_id VARCHAR(36) NOT NULL,
  task_id VARCHAR(36) NOT NULL UNIQUE,
  published BOOLEAN NOT NULL DEFAULT FALSE,
  created_at VARCHAR(40) NOT NULL,
  FOREIGN KEY(snapshot_id) REFERENCES snapshots(id),
  FOREIGN KEY(task_id) REFERENCES tasks(id)
);
CREATE TABLE symbols (
  generation_id VARCHAR(36) NOT NULL,
  id VARCHAR(36) NOT NULL,
  file_id VARCHAR(36) NOT NULL,
  name VARCHAR(512) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  PRIMARY KEY(generation_id,id),
  FOREIGN KEY(generation_id) REFERENCES generations(id)
);
CREATE TABLE relations (
  id VARCHAR(36) PRIMARY KEY,
  generation_id VARCHAR(36) NOT NULL,
  source_id VARCHAR(36) NOT NULL,
  target_id VARCHAR(36),
  payload MEDIUMTEXT NOT NULL,
  FOREIGN KEY(generation_id,source_id) REFERENCES symbols(generation_id,id),
  FOREIGN KEY(generation_id,target_id) REFERENCES symbols(generation_id,id)
);
CREATE TABLE diagnostics (
  id VARCHAR(36) PRIMARY KEY,
  generation_id VARCHAR(36) NOT NULL,
  file_id VARCHAR(36) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  FOREIGN KEY(generation_id) REFERENCES generations(id)
);
CREATE INDEX tasks_snapshot ON tasks(snapshot_id,kind,created_at);
CREATE INDEX symbols_name ON symbols(generation_id,name);
