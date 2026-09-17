-- Email Responsiveness / SLA Management
--
-- This migration is additive only. It does not modify any
-- previously applied migration, table, or column.

-- ---------------------------------------------------------------
-- 1. Configurable customer-domain allowlist.
--
-- SLA eligibility must never hardcode business rules in Java.
-- Which external domains count as "customers" (as opposed to
-- vendors, personal addresses, etc.) is operational data that
-- changes without a deployment, so it lives in the database and
-- is managed through /api/admin/sla/customer-domains rather than
-- application.yaml.
-- ---------------------------------------------------------------
CREATE TABLE customer_domain (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_customer_domain_domain UNIQUE (domain)
);

CREATE INDEX idx_customer_domain_active ON customer_domain(active);

-- ---------------------------------------------------------------
-- 2. Sent Items delta-sync state, one row per mailbox.
--
-- Mirrors email_sync_state (V11/V13), which already tracks Inbox
-- delta state per mailbox_user_id. Sent Items needs its own
-- delta cursor because it is a different Graph mail folder with
-- an independent delta token.
-- ---------------------------------------------------------------
CREATE TABLE email_sent_sync_state (
    mailbox_user_id VARCHAR(100) PRIMARY KEY,
    delta_link TEXT,
    delta_next_link TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------
-- 3. Email SLA tracking.
--
-- Deliberately separate from Task/TaskDuplicateMatch: SLA is an
-- email-level responsiveness measurement and must never be driven
-- by Task status. Only emails that are BOTH from an allowed
-- customer domain AND flagged response_required by the existing
-- email-intelligence pipeline get a row here. Everything else is
-- NOT_APPLICABLE by omission (no row), matching the SLA state
-- model used by SlaService/EmailSlaStatus.
-- ---------------------------------------------------------------
CREATE TABLE email_sla (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Identity of the incoming customer email (idempotency key).
    mailbox_user_id VARCHAR(100) NOT NULL,
    message_id VARCHAR(500) NOT NULL,
    conversation_id VARCHAR(500),

    customer_email VARCHAR(320) NOT NULL,
    customer_domain VARCHAR(255) NOT NULL,
    subject VARCHAR(500),

    received_at TIMESTAMPTZ NOT NULL,
    response_required BOOLEAN NOT NULL DEFAULT TRUE,

    sla_start_at TIMESTAMPTZ NOT NULL,
    sla_deadline_at TIMESTAMPTZ NOT NULL,

    -- Populated once a qualifying outbound reply is matched from
    -- Sent Items. Only the FIRST qualifying reply is stored, even
    -- if the SLA has already been marked BREACHED, so late
    -- responses can still be reported as late (see SlaService).
    first_response_at TIMESTAMPTZ,
    response_message_id VARCHAR(500),
    responded_by VARCHAR(320),

    -- Cached/last-evaluated status. WAITING -> COMPLETED/BREACHED
    -- transitions happen either the moment a reply is matched, or
    -- via the periodic breach-sweep for emails that simply timed
    -- out with no reply. Recomputing this from scratch on every
    -- dashboard read would make daily/trend aggregation expensive;
    -- storing it keeps reporting cheap while status transitions
    -- remain centralized in SlaService, never in the frontend.
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING',

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_email_sla_mailbox_message UNIQUE (mailbox_user_id, message_id),
    CONSTRAINT ck_email_sla_status CHECK (status IN ('WAITING', 'COMPLETED', 'BREACHED'))
);

CREATE INDEX idx_email_sla_status ON email_sla(status);
CREATE INDEX idx_email_sla_received_at ON email_sla(received_at);
CREATE INDEX idx_email_sla_deadline_at ON email_sla(sla_deadline_at);
CREATE INDEX idx_email_sla_mailbox_user_id ON email_sla(mailbox_user_id);
CREATE INDEX idx_email_sla_conversation_id ON email_sla(conversation_id);
CREATE INDEX idx_email_sla_customer_domain ON email_sla(customer_domain);

-- ---------------------------------------------------------------
-- 4. Backfill the two email_intelligence columns this feature
--    depends on, in case any pre-existing rows predate this
--    migration. email_intelligence itself was created empty by
--    V20 and had no writer until this feature, so this is a no-op
--    on a fresh database and only guards against partial/manual
--    data.
-- ---------------------------------------------------------------
UPDATE email_intelligence
SET response_required = COALESCE(response_required, FALSE),
    requires_action = COALESCE(requires_action, FALSE)
WHERE response_required IS NULL
   OR requires_action IS NULL;
