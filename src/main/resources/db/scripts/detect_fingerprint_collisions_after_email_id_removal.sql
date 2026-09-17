-- Diagnostic report for V18__recompute_task_fingerprint_without_email_id.sql.
--
-- Prior to V18, task_fingerprint included the source email's own id,
-- which meant it was unique per email BY CONSTRUCTION — two
-- different emails could never produce the same fingerprint even for
-- byte-identical task text. This silently defeated all cross-email
-- exact-duplicate detection since it was introduced.
--
-- Recomputing the fingerprint without email.id() (V18) may reveal
-- REAL pre-existing duplicate active tasks that this bug was hiding.
-- Run this report first: if it returns rows, V18's guard will refuse
-- to run and point back here.

WITH recomputed AS (
    SELECT
        id,
        source_mailbox,
        normalized_sender,
        status,
        title,
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
)
SELECT
    source_mailbox,
    normalized_sender,
    new_fingerprint,
    COUNT(*)                          AS active_duplicate_count,
    array_agg(id ORDER BY created_at) AS task_ids_oldest_first,
    array_agg(title ORDER BY created_at) AS titles_oldest_first,
    array_agg(status ORDER BY created_at) AS statuses_oldest_first
FROM recomputed
GROUP BY source_mailbox, normalized_sender, new_fingerprint
HAVING COUNT(*) > 1
ORDER BY active_duplicate_count DESC;

-- If this returns rows: these are tasks that the OLD (buggy)
-- fingerprint algorithm treated as distinct purely because they came
-- from different emails, but which are exact-canonical duplicates
-- under the corrected algorithm. Review them like any other
-- detect_active_duplicate_tasks.sql finding, then use
-- db/scripts/archive_duplicate_active_tasks.sql (or a manual
-- decision) to archive all but one per group before running V18.
