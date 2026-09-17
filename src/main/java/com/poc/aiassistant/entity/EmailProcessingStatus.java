package com.poc.aiassistant.entity;

public enum EmailProcessingStatus {

    PENDING,
    PROCESSING,
    COMPLETED,

    /**
     * A short-lived (LlmFailureCategory.TRANSIENT) failure. Retried
     * on the normal short exponential-backoff clock, gated by
     * attempt_count / max-attempts.
     */
    FAILED,

    /**
     * The configured LLM model/provider group was entirely
     * unavailable (LlmFailureCategory.UNAVAILABLE) — not a one-off
     * blip. Retried on a separate, longer defer clock gated by
     * defer_count / max-defer-attempts and a wall-clock defer
     * horizon, NOT the short-retry attempt_count budget. See
     * EmailProcessingService for the full policy.
     */
    DEFERRED,

    DEAD_LETTER,
    SKIPPED
}
