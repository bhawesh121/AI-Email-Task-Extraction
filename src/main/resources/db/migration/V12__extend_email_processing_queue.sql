ALTER TABLE email_processing
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN claimed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN claimed_by VARCHAR(200);

CREATE INDEX idx_email_processing_status_attempt
    ON email_processing(status, next_attempt_at);

CREATE INDEX idx_email_processing_lease
    ON email_processing(status, claimed_at);

UPDATE email_processing
SET next_attempt_at = CURRENT_TIMESTAMP
WHERE status IN ('PENDING', 'FAILED');
