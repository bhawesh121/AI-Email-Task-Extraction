CREATE TABLE email_intelligence (
    id UUID PRIMARY KEY,
    mailbox VARCHAR(320) NOT NULL,
    message_id VARCHAR(500) NOT NULL,
    conversation_id VARCHAR(500),
    received_at TIMESTAMPTZ NOT NULL,
    sender_name VARCHAR(255),
    sender_email VARCHAR(320),
    sender_domain VARCHAR(255),
    subject VARCHAR(500),
    category VARCHAR(80),
    business_function VARCHAR(80),
    topic VARCHAR(120),
    sentiment VARCHAR(20),
    urgency VARCHAR(20),
    response_required BOOLEAN NOT NULL DEFAULT FALSE,
    requires_action BOOLEAN NOT NULL DEFAULT FALSE,
    action_type VARCHAR(80),
    customer_account VARCHAR(320),
    assigned_team VARCHAR(120),
    assigned_person VARCHAR(255),
    potential_opportunity BOOLEAN NOT NULL DEFAULT FALSE,
    opportunity_type VARCHAR(120),
    potential_risk BOOLEAN NOT NULL DEFAULT FALSE,
    risk_category VARCHAR(120),
    deadline TIMESTAMPTZ,
    important_dates TEXT,
    analysis_status VARCHAR(30) NOT NULL DEFAULT 'COMPLETED',
    analysis_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_email_intelligence_mailbox_message UNIQUE (mailbox, message_id)
);

CREATE INDEX idx_email_intelligence_received_at ON email_intelligence(received_at);
CREATE INDEX idx_email_intelligence_topic ON email_intelligence(topic);
CREATE INDEX idx_email_intelligence_sender_email ON email_intelligence(sender_email);
CREATE INDEX idx_email_intelligence_customer_account ON email_intelligence(customer_account);
CREATE INDEX idx_email_intelligence_category ON email_intelligence(category);
CREATE INDEX idx_email_intelligence_opportunity ON email_intelligence(potential_opportunity);
CREATE INDEX idx_email_intelligence_risk ON email_intelligence(potential_risk);
CREATE INDEX idx_email_intelligence_assigned_team ON email_intelligence(assigned_team);
