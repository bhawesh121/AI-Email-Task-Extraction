-- V24
-- Prevents the "Merchant 1" / "Merchant1" duplicate-employee bug from
-- recurring: adds a unique index on the canonical employee key
-- (lower-cased local part of the email address, before '@' — the same
-- rule as EmployeeNameNormalizer.deriveNameFromEmail).
--
-- Before this migration, employees.email had a unique constraint, but
-- it compared the full address case-sensitively and exactly, so two
-- rows whose addresses differed in any way (casing, or genuinely
-- different addresses like "mail" vs "userPrincipalName" for the same
-- Graph user on different sync runs) could both exist even though
-- they represent the same person by this application's own naming
-- rule. TenantEmailSyncService#synchronizeEmployee now also checks
-- for an existing row by canonical name before creating a new one, so
-- in the normal path this index should never actually fire — it's a
-- last-resort safety net at the database layer for any code path that
-- bypasses that check (bulk inserts, other services, future code).
--
-- SAFETY: adding this index will fail if duplicate canonical keys
-- already exist among current rows. As with V15/V18, we check first
-- and raise an actionable error instead of a bare constraint-violation
-- message.
--
-- If this migration halts here, DO NOT bypass the check. Instead:
--   1. Run db/scripts/detect_duplicate_employees.sql to see the
--      offending groups.
--   2. Review and run db/scripts/merge_duplicate_employees.sql to
--      merge each group down to one canonical row.
--   3. Re-run this migration.

DO $$
DECLARE
    violation_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO violation_count
    FROM (
        SELECT lower(split_part(email, '@', 1)) AS canonical_key
        FROM employees
        GROUP BY lower(split_part(email, '@', 1))
        HAVING COUNT(*) > 1
    ) duplicates;

    IF violation_count > 0 THEN
        RAISE EXCEPTION
            'V24 blocked: % employee(s) share a canonical name (mailbox local-part) with at least one '
            'other employee row. Run db/scripts/detect_duplicate_employees.sql to inspect them, then '
            'db/scripts/merge_duplicate_employees.sql to remediate, then re-run this migration.',
            violation_count;
    END IF;
END $$;

CREATE UNIQUE INDEX ux_employees_canonical_name
    ON employees (lower(split_part(email, '@', 1)));