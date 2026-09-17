package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.aiassistant.entity.Task;
import com.poc.aiassistant.service.LlmRateLimiterService;
import com.poc.aiassistant.service.TaskSemanticVerificationService;
import com.poc.aiassistant.service.TaskSemanticVerificationService.Outcome;
import com.poc.aiassistant.service.TaskSemanticVerificationService.Verdict;

/**
 * These tests exercise the verifier's rate-limit gating and its
 * fail-safe-to-AMBIGUOUS behavior WITHOUT making a real HTTP call
 * (no LiteLLM endpoint is reachable in this environment). The actual
 * chat/completions call and JSON response parsing against a real or
 * mocked HTTP server is not covered here — see the limitations note
 * in the final summary. What IS covered: the verifier never returns
 * a SAME/DIFFERENT verdict, and never throws, when the rate limit is
 * exhausted — it must resolve to AMBIGUOUS.
 */
class TaskSemanticVerificationServiceTest {

    /**
     * Deliberately points at a port nothing listens on (localhost:1 is
     * a reserved/privileged port never bound by an application), so
     * the HTTP-failure tests below always produce a connection
     * failure deterministically — independent of whether a real
     * LiteLLM stack happens to be running on the machine executing
     * this suite. Do NOT point this at a real host/port: doing so
     * previously caused httpFailureResolvesToUnavailableOutcome to
     * intermittently observe a genuine (truncated) LLM response
     * instead of a connection failure, asserting the wrong outcome
     * for reasons unrelated to the code under test.
     */
    private static TaskSemanticVerificationService newService(LlmRateLimiterService limiter) {
        return new TaskSemanticVerificationService(
                "http://localhost:1/v1",
                "test-key",
                "fast-chat",
                200,
                "none",
                1000,
                1000,
                new ObjectMapper(),
                limiter
        );
    }

    @Test
    void resolvesToAmbiguousWhenRateLimitBudgetIsAlreadyExhausted() {
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        when(limiter.hasCapacity()).thenReturn(false);

        TaskSemanticVerificationService service = newService(limiter);

        Task newTask = new Task();
        newTask.setTitle("Send contract to John");
        Task candidate = new Task();
        candidate.setTitle("Send the contract to John");

        TaskSemanticVerificationService.VerificationResult result =
                service.verify(newTask, candidate, 0.5, 0.15);

        assertEquals(Verdict.AMBIGUOUS, result.verdict());
    }

    @Test
    void resolvesToAmbiguousWhenAcquiringARateLimitSlotThrows() {
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        when(limiter.hasCapacity()).thenReturn(true);
        org.mockito.Mockito.doThrow(
                new LlmRateLimiterService.LlmRateLimitExceededException("exhausted between check and acquire")
        ).when(limiter).acquireOrThrow();

        TaskSemanticVerificationService service = newService(limiter);

        Task newTask = new Task();
        Task candidate = new Task();

        TaskSemanticVerificationService.VerificationResult result =
                service.verify(newTask, candidate, 0.5, 0.15);

        assertEquals(Verdict.AMBIGUOUS, result.verdict());
    }

    @Test
    void resolvesToAmbiguousWhenTheHttpCallFails() {
        // No LiteLLM endpoint is reachable in this environment, so a
        // real call through verify() (once past the rate limiter)
        // will fail at the network layer. This test asserts that
        // failure surfaces as AMBIGUOUS, not as a thrown exception
        // that would abort task creation.
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        when(limiter.hasCapacity()).thenReturn(true);

        TaskSemanticVerificationService service = newService(limiter);

        Task newTask = new Task();
        newTask.setTitle("Send contract to John");
        Task candidate = new Task();
        candidate.setTitle("Send the contract to John");

        TaskSemanticVerificationService.VerificationResult result =
                service.verify(newTask, candidate, 0.5, 0.15);

        assertEquals(Verdict.AMBIGUOUS, result.verdict());
    }

    // ---- Outcome-level assertions: the new distinction this redesign adds ----

    @Test
    void rateLimitExhaustionResolvesToUnavailableOutcomeNotGenuineAmbiguous() {
        // Same scenario as resolvesToAmbiguousWhenRateLimitBudgetIsAlreadyExhausted
        // above (kept for backward source-compatibility of verdict()),
        // but this is the assertion that actually matters after the
        // redesign: TaskPersistenceService must be able to tell this
        // apart from a genuine model judgment, which it does via
        // outcome(), not verdict().
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        when(limiter.hasCapacity()).thenReturn(false);

        TaskSemanticVerificationService service = newService(limiter);

        Task newTask = new Task();
        Task candidate = new Task();

        TaskSemanticVerificationService.VerificationResult result =
                service.verify(newTask, candidate, 0.5, 0.15);

        assertEquals(Outcome.UNAVAILABLE, result.outcome());
    }

    @Test
    void httpFailureResolvesToUnavailableOutcome() {
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        when(limiter.hasCapacity()).thenReturn(true);

        TaskSemanticVerificationService service = newService(limiter);

        Task newTask = new Task();
        Task candidate = new Task();

        TaskSemanticVerificationService.VerificationResult result =
                service.verify(newTask, candidate, 0.5, 0.15);

        assertEquals(Outcome.UNAVAILABLE, result.outcome());
    }

    @Test
    void wellFormedAmbiguousResponseIsAGenuineVerdictOutcome() {
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        TaskSemanticVerificationService service = newService(limiter);

        TaskSemanticVerificationService.VerificationResult result =
                service.parseVerdict("{\"result\":\"AMBIGUOUS\",\"reason\":\"unclear\"}");

        assertEquals(Verdict.AMBIGUOUS, result.verdict());
        assertEquals(Outcome.VERDICT, result.outcome());
    }

    @Test
    void wellFormedSameResponseIsAGenuineVerdictOutcome() {
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        TaskSemanticVerificationService service = newService(limiter);

        TaskSemanticVerificationService.VerificationResult result =
                service.parseVerdict("{\"result\":\"SAME\",\"reason\":\"same intent\"}");

        assertEquals(Verdict.SAME, result.verdict());
        assertEquals(Outcome.VERDICT, result.outcome());
    }

    @Test
    void unparseableJsonIsMalformedNotAmbiguous() {
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        TaskSemanticVerificationService service = newService(limiter);

        TaskSemanticVerificationService.VerificationResult result =
                service.parseVerdict("this is not json at all {{{");

        assertEquals(Outcome.MALFORMED, result.outcome());
    }

    @Test
    void missingResultFieldIsMalformedNotAmbiguous() {
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        TaskSemanticVerificationService service = newService(limiter);

        TaskSemanticVerificationService.VerificationResult result =
                service.parseVerdict("{\"reason\":\"forgot the result field\"}");

        assertEquals(Outcome.MALFORMED, result.outcome());
    }

    @Test
    void unrecognizedResultValueIsMalformedNotAmbiguous() {
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        TaskSemanticVerificationService service = newService(limiter);

        TaskSemanticVerificationService.VerificationResult result =
                service.parseVerdict("{\"result\":\"MAYBE\",\"reason\":\"not a real verdict value\"}");

        assertEquals(Outcome.MALFORMED, result.outcome());
    }

    @Test
    void codeFencedJsonIsStillParsedAsAGenuineVerdict() {
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        TaskSemanticVerificationService service = newService(limiter);

        TaskSemanticVerificationService.VerificationResult result = service.parseVerdict(
                "```json\n{\"result\":\"DIFFERENT\",\"reason\":\"unrelated tasks\"}\n```"
        );

        assertEquals(Verdict.DIFFERENT, result.verdict());
        assertEquals(Outcome.VERDICT, result.outcome());
    }

    // ---- interpretResponse: the actual finishReason=length path, tested
    // directly without any HTTP call. This is the specific case that a
    // live server can't reliably exercise (a real provider may or may
    // not truncate on any given run), so it must be deterministic and
    // in-process. ----

    @Test
    void finishReasonLengthWithPartialJsonIsMalformedNeverSameOrDifferent() {
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        TaskSemanticVerificationService service = newService(limiter);

        // Exactly the shape observed in real end-to-end testing: the
        // model started writing a SAME/DIFFERENT verdict and got cut
        // off by max_tokens before the JSON object closed.
        TaskSemanticVerificationService.VerificationResult result =
                service.interpretResponse("length", "{\"result\": \"SAM");

        assertEquals(Outcome.MALFORMED, result.outcome());
        assertNotEquals(Verdict.SAME, result.verdict());
        assertNotEquals(Verdict.DIFFERENT, result.verdict());
    }

    @Test
    void finishReasonLengthTakesPrecedenceEvenIfPartialContentLooksParseable() {
        // Defense in depth: even if a truncated response happens to
        // be technically valid JSON (e.g. cut off exactly after a
        // complete-looking object), finishReason=length must still
        // win and produce MALFORMED — never let content-shape alone
        // decide this.
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        TaskSemanticVerificationService service = newService(limiter);

        TaskSemanticVerificationService.VerificationResult result =
                service.interpretResponse("length", "{\"result\":\"SAME\",\"reason\":\"ok\"}");

        assertEquals(Outcome.MALFORMED, result.outcome());
    }

    @Test
    void finishReasonStopWithValidSameJsonIsAGenuineVerdict() {
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        TaskSemanticVerificationService service = newService(limiter);

        TaskSemanticVerificationService.VerificationResult result =
                service.interpretResponse("stop", "{\"result\":\"SAME\",\"reason\":\"same intent\"}");

        assertEquals(Outcome.VERDICT, result.outcome());
        assertEquals(Verdict.SAME, result.verdict());
    }

    @Test
    void finishReasonStopWithValidDifferentJsonIsAGenuineVerdict() {
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        TaskSemanticVerificationService service = newService(limiter);

        TaskSemanticVerificationService.VerificationResult result =
                service.interpretResponse("stop", "{\"result\":\"DIFFERENT\",\"reason\":\"unrelated\"}");

        assertEquals(Outcome.VERDICT, result.outcome());
        assertEquals(Verdict.DIFFERENT, result.verdict());
    }

    @Test
    void blankContentWithNormalFinishReasonIsUnavailableNotMalformed() {
        // No content at all (as opposed to truncated content) is an
        // availability signal, not a malformed-output signal.
        LlmRateLimiterService limiter = mock(LlmRateLimiterService.class);
        TaskSemanticVerificationService service = newService(limiter);

        TaskSemanticVerificationService.VerificationResult result =
                service.interpretResponse("stop", "");

        assertEquals(Outcome.UNAVAILABLE, result.outcome());
    }
}