CREATE TABLE practice_workspaces (
  id VARCHAR(36) PRIMARY KEY,
  task_id VARCHAR(36) NOT NULL,
  task_revision INT NOT NULL,
  snapshot_id VARCHAR(36) NOT NULL,
  path_key VARCHAR(50) NOT NULL UNIQUE,
  status VARCHAR(20) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  FOREIGN KEY(task_id,task_revision) REFERENCES training_task_revisions(task_id,revision),
  FOREIGN KEY(snapshot_id) REFERENCES snapshots(id)
);
CREATE TABLE practice_submissions (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  sequence_no INT NOT NULL,
  content_digest VARCHAR(64) NOT NULL,
  manifest_json MEDIUMTEXT NOT NULL,
  diff_json MEDIUMTEXT NOT NULL,
  path_key VARCHAR(50) NOT NULL UNIQUE,
  submitted_at VARCHAR(40) NOT NULL,
  FOREIGN KEY(workspace_id) REFERENCES practice_workspaces(id),
  UNIQUE(workspace_id,sequence_no)
);
CREATE TABLE practice_test_results (
  id VARCHAR(36) PRIMARY KEY,
  submission_id VARCHAR(36) NOT NULL,
  content_digest VARCHAR(64) NOT NULL,
  source VARCHAR(30) NOT NULL,
  status VARCHAR(20) NOT NULL,
  method VARCHAR(200) NOT NULL,
  environment VARCHAR(1000) NOT NULL,
  report MEDIUMTEXT NOT NULL,
  reported_at VARCHAR(40) NOT NULL,
  FOREIGN KEY(submission_id) REFERENCES practice_submissions(id)
);
