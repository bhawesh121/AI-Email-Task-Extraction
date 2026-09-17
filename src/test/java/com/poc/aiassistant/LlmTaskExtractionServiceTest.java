package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.service.AssigneeResolverService;
import com.poc.aiassistant.service.LlmRateLimiterService;
import com.poc.aiassistant.service.LlmTaskExtractionService;

/**
 * Confirms LlmTaskExtractionService actually throws
 * LlmProviderUnavailableException for a genuine connection-level
 * failure — not just that LlmFailureClassifier would handle it
 * correctly if it did (see LlmFailureClassifierTest for that half).
 *
 * This service builds its own internal RestClient directly from a
 * base-URL string in its constructor, with no seam to inject a mock
 * HTTP client — the same constraint already present for
 * TaskSemanticVerificationService (see
 * TaskSemanticVerificationServiceTest). Rather than invent a
 * different approach for this one test, this mirrors that existing,
 * already-proven pattern: point at http://localhost:1/v1, a reserved
 * port nothing can bind to, which produces a deterministic connection
 * failure regardless of any real LiteLLM stack's state.
 */
class LlmTaskExtractionServiceTest {

    private static LlmTaskExtractionService newService() {
        return new LlmTaskExtractionService(
                "http://localhost:1/v1",
                "test-key",
                "fast-chat",
                512,
                16000,
                "none",
                1000,
                1000,
                new ObjectMapper(),
                mock(AssigneeResolverService.class),
                mock(LlmRateLimiterService.class)
        );
    }

    private static EmailDto email() {
        return new EmailDto(
                "m1",
                "Subject",
                "Sender",
                "sender@example.com",
                "example.com",
                "graph",
                null,
                "Body",
                List.of(),
                List.of(),
                "mailboxA",
                null
        );
    }

    @Test
    void connectionLevelFailureThrowsLlmProviderUnavailableException() {
        LlmTaskExtractionService service = newService();

        assertThrows(
                LlmTaskExtractionService.LlmProviderUnavailableException.class,
                () -> service.extractTasksWithoutRateLimit(email())
        );
    }
}
