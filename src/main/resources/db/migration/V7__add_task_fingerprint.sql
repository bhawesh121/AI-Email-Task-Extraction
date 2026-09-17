-- V7
-- Add a deterministic fingerprint for each extracted task.

ALTER TABLE tasks
ADD COLUMN task_fingerprint VARCHAR(32);

-- Populate fingerprints for existing tasks.
--
-- MD5 is sufficient here because this is being used as a
-- deterministic identity/fingerprint, not as a security hash.

UPDATE tasks
SET task_fingerprint = md5(
    lower(
        trim(coalesce(title, ''))
        || '|'
        || trim(coalesce(description, ''))
        || '|'
        || coalesce(due_date::text, '')
        || '|'
        || coalesce(priority, '')
        || '|'
        || trim(coalesce(assignee, ''))
    )
);

ALTER TABLE tasks
ALTER COLUMN task_fingerprint SET NOT NULL;

-- The same task from the same email must not be inserted twice.
--
-- Different tasks from the same email are allowed.

CREATE UNIQUE INDEX ux_tasks_source_email_fingerprint
ON tasks(source_email_id, task_fingerprint);