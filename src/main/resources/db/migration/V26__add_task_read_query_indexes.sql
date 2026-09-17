-- V26
-- Targeted read-path indexes for the production task APIs.
--
-- These indexes are intentionally narrow. Existing task indexes already cover
-- status, priority, due_date, source_domain, source_type and normalized_sender.
-- The three access patterns below are the remaining high-value gaps visible in
-- the current Tasks & Workload and Dashboard read paths:
--
-- 1. assignee_email is filtered by the paginated task API, while the existing
--    index is on the display-name column `assignee`.
--
-- 2. updated_at is used by the Dashboard's "recent activity" query and the
--    task-list `recent` sort.
--
-- 3. the default task-list ordering and task-trend range use
--    COALESCE(email_received_at, created_at). An expression index matches that
--    actual query expression instead of indexing only one of the two columns.
--
-- Do not add broad multi-column indexes for every possible filter combination:
-- each additional index increases INSERT/UPDATE cost and storage. Revisit with
-- EXPLAIN (ANALYZE, BUFFERS) on representative production data before adding
-- further composite indexes.

CREATE INDEX idx_task_assignee_email
    ON tasks (assignee_email);

CREATE INDEX idx_task_updated_at
    ON tasks (updated_at DESC);

CREATE INDEX idx_task_received_at_coalesce
    ON tasks (
        (COALESCE(email_received_at, created_at)) DESC
    );