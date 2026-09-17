package com.poc.aiassistant.entity;

/**
 * Status of an email-level SLA (response) record.
 *
 * NOT_APPLICABLE is deliberately not a persisted value: an email
 * that is not from an allowed customer domain, or that does not
 * require a response, simply has no {@link EmailSla} row at all.
 * See SlaService for the eligibility decision.
 */
public enum EmailSlaStatus {

    /** Response not yet received and the deadline has not passed. */
    WAITING,

    /** A qualifying reply was sent at or before the SLA deadline. */
    COMPLETED,

    /**
     * Either no qualifying reply exists and the deadline has passed,
     * or a qualifying reply was sent after the deadline. Once an SLA
     * reaches this state it never reverts to COMPLETED, even if a
     * reply is later recorded (see SlaService).
     */
    BREACHED
}
