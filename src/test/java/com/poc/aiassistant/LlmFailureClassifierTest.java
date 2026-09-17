package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.SocketTimeoutException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.poc.aiassistant.service.LlmFailureCategory;
import com.poc.aiassistant.service.LlmFailureClassifier;
import com.poc.aiassistant.service.LlmTaskExtractionService;
import com.poc.aiassistant.service.TaskPersistenceService;

/**
 * Verifies the classifier's decisions using only generic exception
 * types/text — deliberately no provider names (Gemini, NVIDIA, etc.)
 * anywhere in these tests, mirroring the constraint the classifier
 * itself must satisfy. These are plain unit tests; nothing here
 * exercises a real LiteLLM router response.
 */
class LlmFailureClassifierTest {

    @Test
    void nonRetryableExtractionExceptionClassifiesAsNonRetryable() {
        Exception failure = new LlmTaskExtractionService.NonRetryableTaskExtractionException(
                "output truncated by max-tokens"
        );
        assertEquals(LlmFailureCategory.NON_RETRYABLE, LlmFailureClassifier.classify(failure));
    }

    @Test
    void malformedVerificationExceptionClassifiesAsNonRetryable() {
        Exception failure = new TaskPersistenceService.TaskVerificationMalformedException(
                "verifier response truncated by max_tokens"
        );
        assertEquals(LlmFailureCategory.NON_RETRYABLE, LlmFailureClassifier.classify(failure));
    }

    @Test
    void unavailableVerificationExceptionClassifiesAsUnavailable() {
        Exception failure = new TaskPersistenceService.TaskVerificationUnavailableException(
                "verification call failed"
        );
        assertEquals(LlmFailureCategory.UNAVAILABLE, LlmFailureClassifier.classify(failure));
    }

    @Test
    void llmProviderUnavailableExceptionClassifiesAsUnavailable() {
        // The extraction-path equivalent of the verification-path
        // check above: a genuine connection-level failure (no HTTP
        // response at all) must route to the long defer window, not
        // the ordinary short-retry TRANSIENT path.
        Exception failure = new LlmTaskExtractionService.LlmProviderUnavailableException(
                "no response received (connection-level failure)",
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"))
        );
        assertEquals(LlmFailureCategory.UNAVAILABLE, LlmFailureClassifier.classify(failure));
    }

    @Test
    void notFoundResponseClassifiesAsNonRetryable() {
        // A 404 on an OpenAI-compatible chat-completions endpoint
        // means the model/route does not exist at all — a structural,
        // permanent condition (confirmed by a real incident: a
        // retired Gemini model returned exactly this), not something
        // that will ever succeed on retry or after a defer window.
        HttpClientErrorException failure = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"model not found\"}}".getBytes(),
                null
        );
        assertEquals(LlmFailureCategory.NON_RETRYABLE, LlmFailureClassifier.classify(failure));
    }

    @Test
    void notFoundTakesPrecedenceEvenIfBodyAlsoContainsRouterExhaustionText() {
        // Type/status-based classification is intentionally more
        // trusted than the text-matching heuristic — a definitive
        // status code should never be second-guessed by a coincidental
        // word match in the body.
        HttpClientErrorException failure = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                HttpHeaders.EMPTY,
                ("{\"error\":{\"message\":\"model not found; no fallback model group "
                        + "found for original model_group=x\"}}").getBytes(),
                null
        );
        assertEquals(LlmFailureCategory.NON_RETRYABLE, LlmFailureClassifier.classify(failure));
    }

    @Test
    void plainRateLimitResponseWithoutRouterExhaustionTextIsTransient() {
        // A single 429 with no indication from LiteLLM that the whole
        // model group (primary + all fallbacks) is exhausted — this
        // is the "one lucky request away from succeeding" case and
        // should get the ordinary short retry, not a long defer.
        HttpClientErrorException failure = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                "{\"error\":\"rate limited, try again\"}".getBytes(),
                null
        );
        assertEquals(LlmFailureCategory.TRANSIENT, LlmFailureClassifier.classify(failure));
    }

    @Test
    void routerExhaustionTextInResponseBodyIsUnavailableRegardlessOfProvider() {
        // This is LiteLLM's OWN generic router wording, not any
        // provider's — the classifier must recognize it purely from
        // this text, independent of which model names are involved.
        HttpClientErrorException failure = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                ("{\"error\":{\"message\":\"No fallback model group found for original "
                        + "model_group=whatever-was-configured\"}}").getBytes(),
                null
        );
        assertEquals(LlmFailureCategory.UNAVAILABLE, LlmFailureClassifier.classify(failure));
    }

    @Test
    void routerExhaustionTextInCauseChainIsStillDetected() {
        RuntimeException wrapped = new RuntimeException(
                "LiteLLM call failed",
                new RuntimeException("no healthy deployment available for this model group")
        );
        assertEquals(LlmFailureCategory.UNAVAILABLE, LlmFailureClassifier.classify(wrapped));
    }

    @Test
    void serverErrorIsTransient() {
        HttpServerErrorException failure = HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable",
                HttpHeaders.EMPTY,
                new byte[0],
                null
        );
        assertEquals(LlmFailureCategory.TRANSIENT, LlmFailureClassifier.classify(failure));
    }

    @Test
    void connectTimeoutIsTransient() {
        ResourceAccessException failure = new ResourceAccessException(
                "Connection timed out",
                new SocketTimeoutException("Read timed out")
        );
        assertEquals(LlmFailureCategory.TRANSIENT, LlmFailureClassifier.classify(failure));
    }

    @Test
    void unrecognizedFailureDefaultsToTransientNotUnavailable() {
        // The safe default when we don't have a specific signal:
        // short retry, not a multi-hour defer.
        assertEquals(
                LlmFailureCategory.TRANSIENT,
                LlmFailureClassifier.classify(new IllegalStateException("something odd happened"))
        );
    }

    @Test
    void nullFailureIsTreatedAsTransient() {
        assertEquals(LlmFailureCategory.TRANSIENT, LlmFailureClassifier.classify(null));
    }

    @Test
    void retryAfterHeaderIsExtractedGenerically() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "45");
        HttpClientErrorException failure = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers, new byte[0], null
        );
        assertEquals(Duration.ofSeconds(45), LlmFailureClassifier.extractRetryAfter(failure));
    }

    @Test
    void missingRetryAfterReturnsNullSoCallerUsesItsOwnPolicy() {
        HttpClientErrorException failure = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY, new byte[0], null
        );
        assertNull(LlmFailureClassifier.extractRetryAfter(failure));
    }

    @Test
    void nonHttpFailureHasNoRetryAfter() {
        assertNull(LlmFailureClassifier.extractRetryAfter(new IllegalStateException("n/a")));
    }
}
