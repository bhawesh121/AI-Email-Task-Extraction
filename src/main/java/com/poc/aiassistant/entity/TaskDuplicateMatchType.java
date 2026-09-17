package com.poc.aiassistant.entity;

public enum TaskDuplicateMatchType {

    /** Deterministic canonical-fingerprint match (the fast path). */
    EXACT,

    /** LLM semantic verifier judged the tasks equivalent. */
    SEMANTIC_SAME,

    /**
     * LLM semantic verifier could not confidently decide. A new task
     * row IS still created (per the "do not automatically suppress"
     * rule) but is linked here for human review.
     */
    SEMANTIC_AMBIGUOUS
}
