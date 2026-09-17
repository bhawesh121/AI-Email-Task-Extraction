-- V17
-- Durable audit trail for duplicate-task decisions. Without this
-- table, an ALREADY_CREATED outcome (exact or semantic) is
-- invisible: nothing records which email triggered the check, which
-- existing task it matched, or why. This directly answers the
-- auditability requirements:
--   - Which later emails were identified as duplicates?
--   - Why was a task considered the same (canonical or semantic)?
--   - Which logical task was matched?
--
-- Deliberately NOT a modification to the tasks table itself: the
-- original task's creation information must never be overwritten
-- when a duplicate is detected, so this is a separate, append-only
-- log instead of denormalized columns on tasks.
--
-- One row is written per detected match/ambiguity, not per task
-- creation — a clean creation with no candidates or all-DIFFERENT
-- candidates does not produce a row here.

CREATE TABLE task_duplicate_matches (
    id                  UUID PRIMARY KEY,

    -- The existing ACTIVE task considered a possible/actual match.
    matched_task_id     UUID NOT NULL REFERENCES tasks(id),

    -- Populated only for SEMANTIC_AMBIGUOUS: the new task row that
    -- was created anyway (per the "do not automatically suppress"
    -- rule) and should be surfaced for human review. NULL for EXACT
    -- and SEMANTIC_SAME, where no new task row is created.
    new_task_id         UUID REFERENCES tasks(id),

    source_email_id     VARCHAR(255) NOT NULL,
    source_mailbox      VARCHAR(320),
    normalized_sender   VARCHAR(320),

    -- EXACT: canonical fingerprint match (deterministic fast path).
    -- SEMANTIC_SAME: LLM verifier judged the tasks equivalent.
    -- SEMANTIC_AMBIGUOUS: verifier could not confidently decide.
    match_type          VARCHAR(30) NOT NULL,

    -- Short human-readable explanation: "exact canonical fingerprint
    -- match" for EXACT, or the verifier's own stated reason for
    -- SEMANTIC_SAME / SEMANTIC_AMBIGUOUS.
    match_reason         VARCHAR(1000),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_duplicate_matches_matched_task
    ON task_duplicate_matches(matched_task_id);

CREATE INDEX idx_task_duplicate_matches_new_task
    ON task_duplicate_matches(new_task_id);

-- Supports "show me everything needing human review":
-- SELECT * FROM task_duplicate_matches WHERE match_type = 'SEMANTIC_AMBIGUOUS';
CREATE INDEX idx_task_duplicate_matches_type
    ON task_duplicate_matches(match_type);
