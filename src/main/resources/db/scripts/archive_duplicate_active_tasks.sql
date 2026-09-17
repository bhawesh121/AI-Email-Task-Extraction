-- Remediation for duplicate ACTIVE tasks found by
-- detect_active_duplicate_tasks.sql, prior to applying
-- V15__add_active_duplicate_task_index.sql.
--
-- Policy: for each (source_mailbox, normalized_sender,
-- task_fingerprint) group with more than one ACTIVE (non-ARCHIVED)
-- task, keep the OLDEST task active and set every other task in the
-- group to ARCHIVED.
--
-- This matches the project's stated preference (ARCHIVED over
-- physical deletion): no rows are deleted, no title/description/
-- source fields are overwritten, and archived tasks remain fully
-- available for audit/history and for the existing task list UI
-- (they just leave the active workload and stop blocking future
-- duplicate detection for that mailbox+sender+fingerprint group).
--
-- HOW TO RUN THIS SAFELY (psql, interactive):
--   1. psql ... -f db/scripts/detect_active_duplicate_tasks.sql
--      and read the output first.
--   2. Run this whole file in single-transaction mode: psql -1 -f ...
--   3. Inspect the preview SELECT's output and the RETURNING output.
--   4. If it looks correct, run: COMMIT;
--      If not, run: ROLLBACK;
-- This script deliberately does NOT COMMIT on its own.

BEGIN;

-- Step 1: preview which task ids would be archived and why.
WITH duplicate_groups AS (
    SELECT
        id,
        source_mailbox,
        normalized_sender,
        task_fingerprint,
        status,
        created_at,
        ROW_NUMBER() OVER (
            PARTITION BY source_mailbox, normalized_sender, task_fingerprint
            ORDER BY created_at ASC, id ASC
        ) AS rank_within_group
    FROM tasks
    WHERE status <> 'ARCHIVED'
      AND normalized_sender IS NOT NULL
)
SELECT
    id,
    source_mailbox,
    normalized_sender,
    task_fingerprint,
    status AS current_status,
    created_at,
    CASE WHEN rank_within_group = 1
         THEN 'KEEP ACTIVE (oldest)'
         ELSE 'WILL BE ARCHIVED'
    END AS action
FROM duplicate_groups
WHERE (source_mailbox, normalized_sender, task_fingerprint) IN (
    SELECT source_mailbox, normalized_sender, task_fingerprint
    FROM duplicate_groups
    GROUP BY source_mailbox, normalized_sender, task_fingerprint
    HAVING COUNT(*) > 1
)
ORDER BY source_mailbox, normalized_sender, task_fingerprint, rank_within_group;

-- Step 2: apply the archival, keeping the oldest task per group active.
WITH duplicate_groups AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY source_mailbox, normalized_sender, task_fingerprint
            ORDER BY created_at ASC, id ASC
        ) AS rank_within_group
    FROM tasks
    WHERE status <> 'ARCHIVED'
      AND normalized_sender IS NOT NULL
)
UPDATE tasks
SET status = 'ARCHIVED',
    updated_at = now()
WHERE id IN (
    SELECT id FROM duplicate_groups WHERE rank_within_group > 1
)
RETURNING id, source_mailbox, normalized_sender, task_fingerprint, status;

-- Review the RETURNING output above, then either:
--   COMMIT;
-- or
--   ROLLBACK;
