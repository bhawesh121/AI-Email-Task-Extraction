-- V18
-- Fixes a bug in how task_fingerprint was computed: earlier code
-- included the source email's own id in the hash, which made every
-- fingerprint unique per email BY CONSTRUCTION — two different
-- emails could never produce the same fingerprint even for
-- byte-identical task text. This silently defeated
-- findActiveExactMatches and the V15 unique index for their actual
-- purpose (cross-email exact-duplicate detection); only same-email
-- retries were ever correctly deduplicated.
--
-- This migration recomputes task_fingerprint for every row using the
-- corrected formula (title|description|due_date|priority|assignee|
-- assignee_email, matching EmailTaskService#createTaskFingerprint
-- after the fix — see that method for the authoritative Java
-- version). Recomputing ALL rows, not just active ones, keeps the
-- column meaningful for archived rows too (audit/history).
--
-- SAFETY: recomputing may reveal that two ACTIVE tasks — previously
-- treated as distinct only because of the email.id() bug — now
-- collide under the ux_tasks_active_mailbox_sender_fingerprint
-- partial unique index (V15). A bulk UPDATE that produced such a
-- collision would fail the whole statement with a generic
-- constraint-violation error. As with V15, we check first and raise
-- an actionable error instead.
--
-- If this migration halts here, DO NOT bypass the check. Instead:
--   1. Run db/scripts/detect_fingerprint_collisions_after_email_id_removal.sql
--      to see the offending groups. These are real duplicate active
--      tasks that the old bug was hiding.
--   2. Review and run
--      db/scripts/archive_fingerprint_collisions_after_email_id_removal.sql
--      to archive all but the oldest task per group.
--   3. Re-run this migration.

DO $$
DECLARE
    violation_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO violation_count
    FROM (
        SELECT
            source_mailbox,
            normalized_sender,
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
    ) recomputed
    GROUP BY source_mailbox, normalized_sender, new_fingerprint
    HAVING COUNT(*) > 1;

    IF violation_count > 0 THEN
        RAISE EXCEPTION
            'V18 blocked: recomputing task_fingerprint without email.id() would create % active-duplicate '
            'group(s) under ux_tasks_active_mailbox_sender_fingerprint. These are real duplicate tasks the '
            'old fingerprint bug was hiding. Run '
            'db/scripts/detect_fingerprint_collisions_after_email_id_removal.sql to inspect them, then '
            'db/scripts/archive_fingerprint_collisions_after_email_id_removal.sql to remediate, then re-run '
            'this migration.',
            violation_count;
    END IF;
END $$;

UPDATE tasks
SET task_fingerprint = md5(
        lower(regexp_replace(trim(coalesce(title, '')), '\s+', ' ', 'g')) || '|' ||
        lower(regexp_replace(trim(coalesce(description, '')), '\s+', ' ', 'g')) || '|' ||
        coalesce(due_date::text, '') || '|' ||
        coalesce(priority, '') || '|' ||
        lower(regexp_replace(trim(coalesce(assignee, '')), '\s+', ' ', 'g')) || '|' ||
        lower(regexp_replace(trim(coalesce(assignee_email, '')), '\s+', ' ', 'g'))
    );
