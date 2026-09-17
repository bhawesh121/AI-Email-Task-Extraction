-- Diagnostic report: ACTIVE tasks that would violate the
-- (source_mailbox, normalized_sender, task_fingerprint) uniqueness
-- introduced by V15__add_active_duplicate_task_index.sql.
--
-- Read-only. Run this against production BEFORE deploying V15, and
-- again if V15 fails, to see exactly what needs remediation.
--
-- "ACTIVE" here means any status other than ARCHIVED, matching the
-- partial index predicate in V15. Scope includes source_mailbox
-- because this application ingests multiple mailboxes; the same
-- external sender writing to two different mailboxes is NOT a
-- duplicate.

SELECT
    source_mailbox,
    normalized_sender,
    task_fingerprint,
    COUNT(*)                              AS active_duplicate_count,
    array_agg(id ORDER BY created_at)     AS task_ids_oldest_first,
    array_agg(status ORDER BY created_at) AS statuses_oldest_first,
    array_agg(title ORDER BY created_at)  AS titles_oldest_first,
    MIN(created_at)                       AS first_seen_at,
    MAX(created_at)                       AS last_seen_at
FROM tasks
WHERE status <> 'ARCHIVED'
  AND normalized_sender IS NOT NULL
GROUP BY source_mailbox, normalized_sender, task_fingerprint
HAVING COUNT(*) > 1
ORDER BY active_duplicate_count DESC, first_seen_at;

-- How to read this:
--   - task_ids_oldest_first[1] is the task that would normally be
--     treated as the "original" logical task.
--   - Everything after index 1 is a candidate for archival via
--     db/scripts/archive_duplicate_active_tasks.sql, AFTER a human
--     confirms these really are duplicates (this script does not
--     use semantic matching, only the existing exact fingerprint —
--     it will not surface paraphrase-style duplicates, only exact
--     ones, which is all V15's constraint cares about).
