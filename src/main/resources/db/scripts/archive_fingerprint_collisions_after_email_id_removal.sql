-- Remediation for duplicates found by
-- detect_fingerprint_collisions_after_email_id_removal.sql, prior to
-- running V18__recompute_task_fingerprint_without_email_id.sql.
--
-- This is a DIFFERENT script from archive_duplicate_active_tasks.sql:
-- that one groups by the CURRENT stored task_fingerprint column
-- (still including email.id() until V18 runs), so it cannot see
-- these collisions. This script recomputes the fingerprint inline
-- using the same formula V18 will apply, and groups by that instead.
--
-- Same policy as archive_duplicate_active_tasks.sql: keep the oldest
-- task per group active, archive the rest. No deletion, no
-- overwritten fields. Does NOT commit on its own — review the
-- RETURNING output, then COMMIT or ROLLBACK explicitly.

BEGIN;

WITH recomputed AS (
    SELECT
        id,
        source_mailbox,
        normalized_sender,
        status,
        created_at,
        md5(
            lower(regexp_replace(trim(coalesce(title, '')), '\s+', ' ', 'g')) || '|' ||
            lower(regexp_replace(trim(coalesce(description, '')), '\s+', ' ', 'g')) || '|' ||
            coalesce(due_date::text, '') || '|' ||
            coalesce(priority, '') || '|' ||
            lower(regexp_replace(trim(coalesce(assignee, '')), '\s+', ' ', 'g')) || '|' ||
            lower(regexp_replace(trim(coalesce(assignee_email, '')), '\s+', ' ', 'g'))
        ) AS new_fingerprint
    FROM tasks
    WHERE status <> 'ARCHIVED'
      AND normalized_sender IS NOT NULL
),
ranked AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY source_mailbox, normalized_sender, new_fingerprint
            ORDER BY created_at ASC, id ASC
        ) AS rank_within_group
    FROM recomputed
    WHERE (source_mailbox, normalized_sender, new_fingerprint) IN (
        SELECT source_mailbox, normalized_sender, new_fingerprint
        FROM recomputed
        GROUP BY source_mailbox, normalized_sender, new_fingerprint
        HAVING COUNT(*) > 1
    )
)
UPDATE tasks
SET status = 'ARCHIVED',
    updated_at = now()
WHERE id IN (SELECT id FROM ranked WHERE rank_within_group > 1)
RETURNING id, status;

-- Review the RETURNING output above, then either:
--   COMMIT;
-- or
--   ROLLBACK;
