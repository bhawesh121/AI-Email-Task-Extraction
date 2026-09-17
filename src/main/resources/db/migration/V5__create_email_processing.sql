CREATE TABLE email_processing (
    id BIGSERIAL PRIMARY KEY,

    message_id VARCHAR(500) NOT NULL,

    mailbox_user_id VARCHAR(100) NOT NULL,

    mailbox_address VARCHAR(320),

    status VARCHAR(30) NOT NULL,

    processed_at TIMESTAMP WITH TIME ZONE,

    error_message TEXT,

    attempt_count INTEGER NOT NULL DEFAULT 0,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT uk_email_processing_mailbox_message
        UNIQUE (mailbox_user_id, message_id)
);

CREATE INDEX idx_email_processing_status
    ON email_processing(status);

CREATE INDEX idx_email_processing_mailbox
    ON email_processing(mailbox_user_id);