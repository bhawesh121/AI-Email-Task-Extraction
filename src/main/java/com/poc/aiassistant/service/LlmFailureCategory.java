package com.poc.aiassistant.service;

/**
 * How an LLM-call failure should be handled, independent of which
 * model/provider was involved.
 *
 * This is deliberately the ONLY vocabulary the application's retry
 * logic understands. Nothing upstream of {@link LlmFailureClassifier}
 * should ever branch on a provider name, an HTTP status code, or a
 * quota number directly — everything funnels through one of these
 * three buckets first.
 */
public enum LlmFailureCategory {

    /**
     * A short-lived blip: a single timeout, a transient 5xx, a 429
     * that isn't accompanied by a "the whole model group is
     * exhausted" signal. Worth a short exponential-backoff retry of
     * the whole email-processing operation.
     */
    TRANSIENT,

    /**
     * The configured model group (primary + every fallback LiteLLM
     * was told about) is currently unable to serve requests at all —
     * e.g. LiteLLM itself reports it exhausted every deployment in
     * the routing chain. This is a duration/availability condition,
     * not a one-off blip, and must NOT be retried on the same short
     * clock as {@link #TRANSIENT} — see the DEFERRED processing
     * state in EmailProcessingService.
     */
    UNAVAILABLE,

    /**
     * The failure will reproduce identically on retry given the
     * current configuration (e.g. output truncated at max-tokens, or
     * a response that parsed but was structurally invalid). Retrying
     * — on any clock, short or deferred — cannot fix this; only a
     * configuration change can. Handled as dead-letter-with-a-clear-
     * reason plus a manual requeue path, not an automatic retry.
     */
    NON_RETRYABLE
}
