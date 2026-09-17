ALTER TABLE tasks
ADD COLUMN source_domain VARCHAR(255);

ALTER TABLE tasks
ADD COLUMN source_type VARCHAR(30);

CREATE INDEX idx_task_source_domain
ON tasks(source_domain);

CREATE INDEX idx_task_source_type
ON tasks(source_type);