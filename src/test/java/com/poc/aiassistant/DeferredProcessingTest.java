package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.web.client.HttpClientErrorException;

import com.poc.aiassistant.entity.EmailProcessing;
import com.poc.aiassistant.entity.EmailProcessingStatus;
import com.poc.aiassistant.repository.EmailProcessingRepository;
import com.poc.aiassistant.service.EmailProcessingService;
import com.poc.aiassistant.service.EmailTaskService;
import com.poc.aiassistant.service.SlaService;
import com.poc.aiassistant.service.LlmTaskExtractionService;
import com.poc.aiassistant.service.TaskPersistenceService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * Covers the behavior this whole redesign exists for: a failure
 * classifying as UNAVAILABLE must land in DEFERRED on its own budget
 * (never touching attempt_count), stay recoverable, only escalate to
 * DEAD_LETTER after the configured defer horizon, and a
 * NON_RETRYABLE failure must dead-letter immediately with a manual
 * requeue path — as distinct, independently testable behaviors from
 * the pre-existing TRANSIENT/FAILED short-retry path (which is left
 * untouched and still covered by EmailProcessingQueueTest).
 *
 * These are Mockito-based unit tests against EmailProcessingService
 * directly — no real PostgreSQL, no real LiteLLM router. They verify
 * the classification->state-transition logic in isolation; they do
 * NOT verify claimBatch's SQL actually re-selects a DEFERRED row from
 * a real database (see EmailQueueBatchClaimTest for the SQL shape,
 * also mock-based, not run against real Postgres in this session).
 */
class DeferredProcessingTest {

    private EmailProcessingRepository repository;
    private EmailTaskService taskService;
    private EntityManager entityManager;
    private JdbcOperations jdbc;
    private EmailProcessingService service;

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_DEFER_ATTEMPTS = 100;
    private static final int DEFERRED_RETRY_INTERVAL_MINUTES = 20;
    private static final int MAX_DEFER_MINUTES = 2880; // 48h

    @BeforeEach
    void setUp() {
        repository = mock(EmailProcessingRepository.class);
        taskService = mock(EmailTaskService.class);
        entityManager = mock(EntityManager.class);
        jdbc = mock(JdbcOperations.class);
        service = new EmailProcessingService(
                repository,
                taskService,
                mock(SlaService.class),
                entityManager,
                jdbc,
                MAX_ATTEMPTS,
                15,
                60,
                MAX_DEFER_ATTEMPTS,
                DEFERRED_RETRY_INTERVAL_MINUTES,
                MAX_DEFER_MINUTES
        );
    }

    private EmailProcessing newProcessing(
            Long id,
            EmailProcessingStatus status,
            int attempts
    ) {
        EmailProcessing p = new EmailProcessing();
        p.setMessageId("m1");
        p.setMailboxUserId("u1");
        p.setStatus(status);
        p.setAttemptCount(attempts);
        return p;
    }

    private void stubOwned(Long id, EmailProcessing processing) {
        processing.setClaimedBy("worker-1");
        processing.setClaimedAt(OffsetDateTime.now(ZoneOffset.UTC));
        when(entityManager.find(
                eq(EmailProcessing.class),
                eq(id),
                eq(LockModeType.PESSIMISTIC_WRITE)
        )).thenReturn(processing);
    }

    // ---- UNAVAILABLE -> DEFERRED, not FAILED, attempt_count untouched ----

    @Test
    void routerExhaustionFailureDefersInsteadOfFailing() {
        EmailProcessing processing = newProcessing(7L, EmailProcessingStatus.PROCESSING, 1);
        stubOwned(7L, processing);

        Exception unavailable = new TaskPersistenceService.TaskVerificationUnavailableException(
                "all configured providers unavailable"
        );

        service.recordFailure(7L, "worker-1", unavailable);

        assertEquals(EmailProcessingStatus.DEFERRED, processing.getStatus());
        assertEquals(1, processing.getAttemptCount(), "attempt_count must not be touched by a defer");
        assertEquals(1, processing.getDeferCount());
        assertNotNull(processing.getFirstDeferredAt());
        assertNotNull(processing.getNextAttemptAt());
        assertTrue(processing.getNextAttemptAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
        assertEquals("UNAVAILABLE", processing.getFailureCategory());
    }

    @Test
    void llmProviderUnavailableFailureDefersInsteadOfFailing() {
        // The extraction-path counterpart to the verification-path
        // test above: a genuine connection-level extraction failure
        // must land in DEFERRED on its own budget, not FAILED on the
        // ordinary short-retry budget.
        EmailProcessing processing = newProcessing(7L, EmailProcessingStatus.PROCESSING, 1);
        stubOwned(7L, processing);

        Exception unavailable = new LlmTaskExtractionService.LlmProviderUnavailableException(
                "no response received (connection-level failure)",
                new RuntimeException("connection refused")
        );

        service.recordFailure(7L, "worker-1", unavailable);

        assertEquals(EmailProcessingStatus.DEFERRED, processing.getStatus());
        assertEquals(1, processing.getAttemptCount(), "attempt_count must not be touched by a defer");
        assertEquals(1, processing.getDeferCount());
        assertNotNull(processing.getFirstDeferredAt());
        assertNotNull(processing.getNextAttemptAt());
        assertTrue(processing.getNextAttemptAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
        assertEquals("UNAVAILABLE", processing.getFailureCategory());
    }

    @Test
    void deferredNextAttemptUsesConfiguredIntervalWhenNoRetryAfterPresent() {
        EmailProcessing processing = newProcessing(7L, EmailProcessingStatus.PROCESSING, 0);
        stubOwned(7L, processing);

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        service.recordFailure(
                7L, "worker-1",
                new TaskPersistenceService.TaskVerificationUnavailableException("down")
        );

        Duration untilNextAttempt = Duration.between(before, processing.getNextAttemptAt());
        // Allow slack for test execution time; the point is it's the
        // configured ~20 minutes, not the short-retry ~30s formula.
        assertTrue(untilNextAttempt.toMinutes() >= DEFERRED_RETRY_INTERVAL_MINUTES - 1);
    }

    @Test
    void deferredNextAttemptHonorsRetryAfterHeaderWhenPresent() {
        EmailProcessing processing = newProcessing(7L, EmailProcessingStatus.PROCESSING, 0);
        stubOwned(7L, processing);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "300"); // 5 minutes
        HttpClientErrorException withRetryAfter = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                headers,
                ("{\"error\":\"no fallback model group found for original model_group=x\"}").getBytes(),
                null
        );

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        service.recordFailure(7L, "worker-1", withRetryAfter);

        assertEquals(EmailProcessingStatus.DEFERRED, processing.getStatus());
        Duration untilNextAttempt = Duration.between(before, processing.getNextAttemptAt());
        // Should use the provider-supplied 5 minutes, NOT the
        // configured 20-minute default policy.
        assertTrue(untilNextAttempt.toMinutes() <= 6);
    }

    @Test
    void repeatedDeferralsDoNotIncrementAttemptCount() {
        EmailProcessing processing = newProcessing(7L, EmailProcessingStatus.PROCESSING, 0);
        OffsetDateTime firstDeferredAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);

        for (int i = 0; i < 5; i++) {
            processing.setStatus(EmailProcessingStatus.PROCESSING);
            stubOwned(7L, processing);
            if (i == 0) {
                // Simulate this being an already-deferred row on
                // subsequent cycles: firstDeferredAt persists across
                // the whole defer episode.
            } else {
                processing.setFirstDeferredAt(firstDeferredAt);
            }
            service.recordFailure(
                    7L, "worker-1",
                    new TaskPersistenceService.TaskVerificationUnavailableException("still down")
            );
        }

        assertEquals(0, processing.getAttemptCount(), "short-retry budget must stay untouched across many defers");
        assertEquals(5, processing.getDeferCount());
    }

    // ---- Defer horizon exceeded -> DEAD_LETTER (wall-clock, not attempt-count driven) ----

    @Test
    void exceedingDeferHorizonMovesToDeadLetterEvenWithLowDeferCount() {
        EmailProcessing processing = newProcessing(7L, EmailProcessingStatus.PROCESSING, 0);
        // Already deferred for longer than max-defer-minutes (48h),
        // e.g. one long-running outage rather than many short cycles
        // — the horizon is wall-clock based, not defer_count based.
        processing.setFirstDeferredAt(
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(MAX_DEFER_MINUTES + 10)
        );
        processing.setDeferCount(1);
        stubOwned(7L, processing);

        service.recordFailure(
                7L, "worker-1",
                new TaskPersistenceService.TaskVerificationUnavailableException("still down")
        );

        assertEquals(EmailProcessingStatus.DEAD_LETTER, processing.getStatus());
        assertNull(processing.getNextAttemptAt());
    }

    @Test
    void exceedingMaxDeferAttemptsMovesToDeadLetterEvenWithinHorizon() {
        EmailProcessing processing = newProcessing(7L, EmailProcessingStatus.PROCESSING, 0);
        processing.setFirstDeferredAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        processing.setDeferCount(MAX_DEFER_ATTEMPTS); // about to exceed
        stubOwned(7L, processing);

        service.recordFailure(
                7L, "worker-1",
                new TaskPersistenceService.TaskVerificationUnavailableException("still down")
        );

        assertEquals(EmailProcessingStatus.DEAD_LETTER, processing.getStatus());
    }

    // ---- NON_RETRYABLE -> immediate DEAD_LETTER regardless of attempt_count ----

    @Test
    void nonRetryableFailureDeadLettersImmediatelyOnFirstAttempt() {
        EmailProcessing processing = newProcessing(7L, EmailProcessingStatus.PROCESSING, 0);
        stubOwned(7L, processing);

        service.recordFailure(
                7L, "worker-1",
                new LlmTaskExtractionService.NonRetryableTaskExtractionException(
                        "truncated by max-tokens"
                )
        );

        assertEquals(EmailProcessingStatus.DEAD_LETTER, processing.getStatus());
        assertEquals("NON_RETRYABLE", processing.getFailureCategory());
        assertEquals(0, processing.getAttemptCount(), "must not have consumed the short-retry budget");
    }

    @Test
    void malformedVerifierResponseDeadLettersImmediatelyNotDeferred() {
        EmailProcessing processing = newProcessing(7L, EmailProcessingStatus.PROCESSING, 0);
        stubOwned(7L, processing);

        service.recordFailure(
                7L, "worker-1",
                new TaskPersistenceService.TaskVerificationMalformedException(
                        "verifier response truncated by max_tokens"
                )
        );

        assertEquals(
                EmailProcessingStatus.DEAD_LETTER,
                processing.getStatus(),
                "a deterministic bad-output failure must not be auto-deferred; it needs a config fix"
        );
        assertEquals(0, processing.getDeferCount());
    }

    // ---- Manual requeue path ----

    @Test
    void requeueDeadLetterResetsAllRetryStateAndReturnsPending() {
        EmailProcessing processing = newProcessing(9L, EmailProcessingStatus.DEAD_LETTER, 5);
        processing.setDeferCount(12);
        processing.setFirstDeferredAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(3));
        processing.setFailureCategory("NON_RETRYABLE");
        processing.setErrorMessage("output truncated by max-tokens");
        when(entityManager.find(EmailProcessing.class, 9L)).thenReturn(processing);

        boolean requeued = service.requeueDeadLetter(9L);

        assertTrue(requeued);
        assertEquals(EmailProcessingStatus.PENDING, processing.getStatus());
        assertEquals(0, processing.getAttemptCount());
        assertEquals(0, processing.getDeferCount());
        assertNull(processing.getFirstDeferredAt());
        assertNull(processing.getFailureCategory());
        assertNull(processing.getErrorMessage());
        assertNotNull(processing.getNextAttemptAt());
    }

    @Test
    void requeueingANonDeadLetterRowIsRejected() {
        EmailProcessing processing = newProcessing(9L, EmailProcessingStatus.FAILED, 1);
        when(entityManager.find(EmailProcessing.class, 9L)).thenReturn(processing);

        boolean requeued = service.requeueDeadLetter(9L);

        assertFalse(requeued);
        assertEquals(EmailProcessingStatus.FAILED, processing.getStatus(), "must not be mutated");
    }

    // ---- Regression: ordinary TRANSIENT failures unaffected by this redesign ----

    @Test
    void plainRuntimeExceptionStillUsesOrdinaryShortRetryFailedPath() {
        // attemptCount is incremented by markAttemptStarted() before
        // extraction runs (not by recordFailure itself) — so calling
        // recordFailure directly, as in the rest of this test class,
        // should leave the count exactly as constructed here.
        EmailProcessing processing = newProcessing(7L, EmailProcessingStatus.PROCESSING, 1);
        stubOwned(7L, processing);

        service.recordFailure(7L, "worker-1", new RuntimeException("temporary blip"));

        assertEquals(EmailProcessingStatus.FAILED, processing.getStatus());
        assertEquals(1, processing.getAttemptCount());
        assertEquals(0, processing.getDeferCount(), "a transient failure must never touch the defer budget");
        assertEquals("TRANSIENT", processing.getFailureCategory());
    }
}
