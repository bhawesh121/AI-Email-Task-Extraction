-- Diagnostic report: employee rows that represent the same person
-- under the canonical-name rule (lower-cased local part of the email
-- address, before '@' — see EmployeeNameNormalizer.deriveNameFromEmail),
-- but currently exist as separate rows with separate display names
-- (e.g. "Merchant 1" vs "Merchant1").
--
-- Read-only. Run this BEFORE merge_duplicate_employees.sql, and again
-- after, to confirm the merge worked and nothing new appeared.
--
-- task_count is the number of tasks currently pointing at this
-- employee's email via tasks.assignee_email (case-insensitive), which
-- is what merge_duplicate_employees.sql uses to pick which row is
-- "canonical" (kept) vs "duplicate" (merged away).

SELECT
    lower(split_part(e.email, '@', 1))              AS canonical_key,
    e.id,
    e.name,
    e.email,
    e.microsoft_user_id,
    (
        SELECT COUNT(*)
        FROM tasks t
        WHERE lower(trim(t.assignee_email)) = lower(trim(e.email))
    )                                                 AS task_count
FROM employees e
WHERE lower(split_part(e.email, '@', 1)) IN (
    SELECT lower(split_part(email, '@', 1))
    FROM employees
    GROUP BY lower(split_part(email, '@', 1))
    HAVING COUNT(*) > 1
)
ORDER BY canonical_key, task_count DESC, e.email;

-- How to read this: each canonical_key group with more than one row
-- is a duplicate-employee group. Within a group, the row with the
-- highest task_count is what merge_duplicate_employees.sql will keep
-- as canonical by default; everything else in the group gets its
-- tasks reassigned and the row deleted. Review before running the
-- merge — this script makes no changes.