-- V14
-- Add the sender-identity scoping column used for logical-task
-- duplicate detection (see business rule: SAME SENDER + SAME
-- SEMANTIC TASK + matching task ACTIVE => ALREADY_CREATED).
--
-- This migration is purely additive: it adds a nullable column and
-- backfills it from existing data. It does not add any constraint,
-- so it cannot fail due to pre-existing duplicate rows. The
-- enforcing constraint is added separately in V15, after existing
-- data has been checked (see db/scripts/detect_active_duplicate_tasks.sql).

ALTER TABLE tasks
ADD COLUMN normalized_sender VARCHAR(320);

-- Backfill using the same rule as SenderNormalizer.normalize():
-- trim + lower-case. Rows with a null/blank source_sender
-- (e.g. tasks created manually via the API, not from an email)
-- are intentionally left NULL and are excluded from duplicate
-- scoping.
UPDATE tasks
SET normalized_sender = lower(trim(source_sender))
WHERE source_sender IS NOT NULL
  AND trim(source_sender) <> '';

CREATE INDEX idx_task_normalized_sender
ON tasks(normalized_sender);
