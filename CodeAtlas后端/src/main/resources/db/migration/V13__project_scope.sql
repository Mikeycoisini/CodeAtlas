ALTER TABLE learning_records ADD COLUMN project_id VARCHAR(36);
ALTER TABLE training_tasks ADD COLUMN project_id VARCHAR(36);
ALTER TABLE guide_runs ADD COLUMN project_id VARCHAR(36);
UPDATE learning_records r SET project_id=(SELECT MIN(e.project_id) FROM learning_sources l JOIN evidence_sources e ON e.id=l.source_id WHERE l.record_id=r.id HAVING COUNT(DISTINCT e.project_id)=1);
UPDATE training_tasks t SET project_id=(SELECT MIN(e.project_id) FROM training_task_sources s JOIN evidence_sources e ON e.id=s.source_id WHERE s.task_id=t.id AND s.task_revision=t.revision HAVING COUNT(DISTINCT e.project_id)=1);
UPDATE learning_records r SET project_id=(SELECT k.project_id FROM (SELECT id,MAX(project_id) AS project_id FROM learning_records GROUP BY id) k WHERE k.id=r.knowledge_id) WHERE r.project_id IS NULL AND r.knowledge_id IS NOT NULL;
CREATE INDEX learning_records_project_kind ON learning_records(project_id,kind,updated_at);
CREATE INDEX training_tasks_project_updated ON training_tasks(project_id,updated_at);
CREATE INDEX guide_runs_project_updated ON guide_runs(project_id,updated_at_ms);
