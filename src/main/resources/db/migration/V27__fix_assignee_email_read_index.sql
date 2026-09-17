-- V27
-- Replace the plain assignee_email index with an expression index
-- matching the case-insensitive predicate used by TaskService.

DROP INDEX IF EXISTS idx_task_assignee_email;

CREATE INDEX idx_task_assignee_email_lower
    ON tasks (LOWER(assignee_email));