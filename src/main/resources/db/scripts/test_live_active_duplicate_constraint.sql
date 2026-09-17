-- Verifies that ux_tasks_active_mailbox_sender_fingerprint (V15) is
-- actually live and actually blocks a duplicate active task insert.
--
-- Safe to run against your real dev/staging database: everything
-- happens inside one transaction that is explicitly ROLLBACK'd at
-- the end, so nothing is left behind either way.
--
-- HOW TO RUN:
--   psql -h localhost -p 5433 -U <user> -d aiassistant -f test_live_active_duplicate_constraint.sql
--
-- EXPECTED OUTPUT: the second INSERT fails with something like
--   ERROR:  duplicate key value violates unique constraint "ux_tasks_active_mailbox_sender_fingerprint"
--   DETAIL:  Key (source_mailbox, normalized_sender, task_fingerprint)=(test-mailbox, test-sender@example.com, deadbeefdeadbeefdeadbeefdeadbeef) already exists.
-- If you see that error, the constraint is working. If both INSERTs
-- succeed, something is wrong (constraint missing/not applied).

BEGIN;

-- First task: succeeds normally.
INSERT INTO tasks (
    id, title, priority, status,
    source_mailbox, normalized_sender, task_fingerprint,
    created_at, updated_at
) VALUES (
    gen_random_uuid(), 'TEST: constraint check task', 'MEDIUM', 'NEW',
    'test-mailbox', 'test-sender@example.com', 'deadbeefdeadbeefdeadbeefdeadbeef',
    now(), now()
);

-- Second task: same (source_mailbox, normalized_sender, task_fingerprint),
-- still ACTIVE (status <> 'ARCHIVED'). This INSERT is EXPECTED TO FAIL.
INSERT INTO tasks (
    id, title, priority, status,
    source_mailbox, normalized_sender, task_fingerprint,
    created_at, updated_at
) VALUES (
    gen_random_uuid(), 'TEST: should be rejected as an active duplicate', 'MEDIUM', 'NEW',
    'test-mailbox', 'test-sender@example.com', 'deadbeefdeadbeefdeadbeefdeadbeef',
    now(), now()
);

-- Not reached if the constraint fired correctly (Postgres aborts the
-- transaction on the error above; this ROLLBACK is what you'd run
-- manually if, for some reason, both inserts succeeded).
ROLLBACK;
