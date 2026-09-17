-- V6
-- Remove the old constraint that incorrectly allowed
-- only one task for each source email.

DROP INDEX IF EXISTS ux_tasks_source_email_id;