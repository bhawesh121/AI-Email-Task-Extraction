-- V9__add_excel_sync_status.sql
ALTER TABLE tasks
    ADD COLUMN excel_synced BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE tasks
    ADD COLUMN excel_sync_error VARCHAR(1000);

ALTER TABLE tasks
    ADD COLUMN excel_sync_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE tasks
    ADD COLUMN excel_last_attempt_at TIMESTAMPTZ;

CREATE INDEX idx_task_excel_synced
    ON tasks (excel_synced);