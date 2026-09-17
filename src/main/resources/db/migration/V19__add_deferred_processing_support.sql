-- Supports the DEFERRED email_processing status: a distinct retry
-- budget/horizon for "the whole configured LLM model group was
-- unavailable" (LlmFailureCategory.UNAVAILABLE), kept separate from
-- attempt_count (the short-retry budget for ordinary transient
-- failures). See EmailProcessingService and LlmFailureClassifier.
--
-- email_processing.status is VARCHAR(30) (see V5), not a native
-- Postgres enum, so adding the new 'DEFERRED' value requires no
-- migration of the column itself — only these new columns.

ALTER TABLE email_processing
    ADD COLUMN defer_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE email_processing
    ADD COLUMN first_deferred_at TIMESTAMP WITH TIME ZONE;

-- Structured failure attribution, independent of the free-text
-- error_message column, so operators can query "how many emails are
-- currently stuck on provider unavailability" vs. "how many are
-- stuck on a bad/malformed LLM response" without parsing text.
-- Nullable: only set when a failure has actually been classified;
-- COMPLETED/PENDING/PROCESSING/SKIPPED rows have no failure to
-- categorize.
ALTER TABLE email_processing
    ADD COLUMN failure_category VARCHAR(30);

CREATE INDEX idx_email_processing_deferred
    ON email_processing (status, next_attempt_at)
    WHERE status = 'DEFERRED';
