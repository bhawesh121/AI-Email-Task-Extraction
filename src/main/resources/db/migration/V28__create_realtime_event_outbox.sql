CREATE TABLE realtime_event_outbox (
    id UUID PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    claimed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(2000),
    CONSTRAINT ck_realtime_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED'))
);

CREATE INDEX idx_realtime_outbox_pending
    ON realtime_event_outbox (status, next_attempt_at, created_at);

CREATE INDEX idx_realtime_outbox_published_at
    ON realtime_event_outbox (published_at);
