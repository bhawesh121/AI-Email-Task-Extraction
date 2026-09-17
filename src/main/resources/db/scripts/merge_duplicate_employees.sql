-- Remediation for duplicate employee rows found by
-- detect_duplicate_employees.sql — the "Merchant 1" vs "Merchant1"
-- bug, where the same real mailbox ended up as two Employee rows
-- (usually because Graph displayName drifted, or the same mailbox
-- was synced under two different email/graph-id strings before
-- EmployeeNameNormalizer + the name-fallback lookup in
-- TenantEmailSyncService#synchronizeEmployee existed).
--
-- Policy: within each group of employees that share the same
-- canonical key (lower-cased local part of the email, before '@'),
-- keep the row with the MOST tasks currently assigned to it (ties
-- broken by email, ascending, for determinism) as canonical. Every
-- other row in the group has its tasks reassigned to the canonical
-- row, then is deleted. The canonical row's `name` is rewritten to
-- the canonical form so the surviving label matches what
-- EmployeeNameNormalizer would derive going forward (e.g. "merchant1",
-- not "Merchant 1").
--
-- This does NOT delete or archive any tasks — only reassigns
-- assignee / assignee_email on existing rows, matching this project's
-- stated preference for reassignment/archival over deletion of task
-- data (see archive_duplicate_active_tasks.sql). Only employees rows
-- are deleted, and only the ones being merged away.
--
-- HOW TO RUN THIS SAFELY (psql, interactive):
--   1. psql ... -f db/scripts/detect_duplicate_employees.sql
--      and read the output first. If the "canonical" row this script
--      would pick (highest task_count) is NOT the one you want kept
--      (e.g. you'd rather keep the row with the real/primary email
--      even though it currently has fewer tasks), stop and do it
--      manually instead — this script does not have a way to
--      override the pick.
--   2. Run this whole file in single-transaction mode: psql -1 -f ...
--   3. Inspect the preview SELECT's output and the RETURNING output.
--   4. If it looks correct, run: COMMIT;
--      If not, run: ROLLBACK;
-- This script deliberately does NOT COMMIT on its own.

BEGIN;

-- Step 1: preview which employee rows are canonical vs duplicate,
-- and how many tasks each duplicate will hand off.
WITH ranked AS (
    SELECT
        e.id,
        e.name,
        e.email,
        e.microsoft_user_id,
        lower(split_part(e.email, '@', 1)) AS canonical_key,
        (
            SELECT COUNT(*)
            FROM tasks t
            WHERE lower(trim(t.assignee_email)) = lower(trim(e.email))
        ) AS task_count,
        ROW_NUMBER() OVER (
            PARTITION BY lower(split_part(e.email, '@', 1))
            ORDER BY
                (
                    SELECT COUNT(*)
                    FROM tasks t
                    WHERE lower(trim(t.assignee_email)) = lower(trim(e.email))
                ) DESC,
                e.email ASC
        ) AS rank_within_group
    FROM employees e
)
SELECT
    canonical_key,
    id,
    name AS current_name,
    email,
    microsoft_user_id,
    task_count,
    CASE WHEN rank_within_group = 1
         THEN 'KEEP (canonical), rename to canonical_key'
         ELSE 'MERGE AWAY: reassign tasks, then delete'
    END AS action
FROM ranked
WHERE canonical_key IN (
    SELECT lower(split_part(email, '@', 1))
    FROM employees
    GROUP BY lower(split_part(email, '@', 1))
    HAVING COUNT(*) > 1
)
ORDER BY canonical_key, rank_within_group;

-- Step 2: build the canonical mapping once so every subsequent
-- statement in this transaction agrees on who's canonical.
CREATE TEMP TABLE employee_merge_plan ON COMMIT DROP AS
WITH ranked AS (
    SELECT
        e.id,
        e.email,
        lower(split_part(e.email, '@', 1)) AS canonical_key,
        ROW_NUMBER() OVER (
            PARTITION BY lower(split_part(e.email, '@', 1))
            ORDER BY
                (
                    SELECT COUNT(*)
                    FROM tasks t
                    WHERE lower(trim(t.assignee_email)) = lower(trim(e.email))
                ) DESC,
                e.email ASC
        ) AS rank_within_group
    FROM employees e
)
SELECT
    dup.id            AS duplicate_id,
    dup.email         AS duplicate_email,
    canon.id          AS canonical_id,
    canon.email       AS canonical_email,
    canon.canonical_key
FROM ranked dup
JOIN ranked canon
    ON canon.canonical_key = dup.canonical_key
   AND canon.rank_within_group = 1
WHERE dup.rank_within_group > 1;

-- Step 3: rewrite the canonical row's name to the canonical form.
UPDATE employees
SET name = plan.canonical_key
FROM (
    SELECT DISTINCT canonical_id, canonical_key FROM employee_merge_plan
) plan
WHERE employees.id = plan.canonical_id
RETURNING employees.id, employees.email, employees.name;

-- Step 4: reassign every task currently pointing at a duplicate
-- employee (by assignee_email) over to the canonical employee.
UPDATE tasks
SET assignee = canon.name,
    assignee_email = canon.email
FROM employee_merge_plan plan
JOIN employees canon ON canon.id = plan.canonical_id
WHERE lower(trim(tasks.assignee_email)) = lower(trim(plan.duplicate_email))
RETURNING tasks.id, tasks.assignee, tasks.assignee_email;

-- Step 5: belt-and-suspenders — also catch tasks that only ever had
-- assignee_email populated for the DUPLICATE row's own historical
-- name (no email at all on the task, or an email that didn't match
-- either row exactly, but the free-text assignee name did). Matches
-- against the duplicate's *original* name, captured before step 3
-- renamed anything, via the employees row itself only if it still
-- existed — since step 3 only touched the canonical row, duplicate
-- rows still have their original name here.
UPDATE tasks
SET assignee = canon.name,
    assignee_email = canon.email
FROM employee_merge_plan plan
JOIN employees dup ON dup.id = plan.duplicate_id
JOIN employees canon ON canon.id = plan.canonical_id
WHERE tasks.assignee_email IS NULL
  AND lower(trim(tasks.assignee)) = lower(trim(dup.name))
RETURNING tasks.id, tasks.assignee, tasks.assignee_email;

-- Step 6: delete the now-empty duplicate employee rows.
DELETE FROM employees
WHERE id IN (SELECT duplicate_id FROM employee_merge_plan)
RETURNING id, email;

-- Review all the RETURNING output above, then either:
--   COMMIT;
-- or
--   ROLLBACK;