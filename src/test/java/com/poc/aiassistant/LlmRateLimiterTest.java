package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.poc.aiassistant.service.LlmRateLimiterService;

class LlmRateLimiterTest {

    @Test
    void perMinuteCapacityIsEnforced() {
        LlmRateLimiterService limiter = new LlmRateLimiterService(1, 10, "UTC");
        assertTrue(limiter.hasCapacity());
        limiter.acquireOrThrow();
        assertFalse(limiter.hasCapacity());
        assertThrows(LlmRateLimiterService.LlmRateLimitExceededException.class, limiter::acquireOrThrow);
    }

    @Test
    void perDayCapacityIsEnforced() {
        LlmRateLimiterService limiter = new LlmRateLimiterService(10, 1, "UTC");
        limiter.acquireOrThrow();
        assertFalse(limiter.hasCapacity());
        assertThrows(LlmRateLimiterService.LlmRateLimitExceededException.class, limiter::acquireOrThrow);
    }

    @Test
    void rateLimitExceptionDoesNotChangeQueueAttemptByItself() {
        // The queue owns attempt accounting; this test documents the limiter's scope.
        LlmRateLimiterService limiter = new LlmRateLimiterService(0, 0, "UTC");
        assertFalse(limiter.hasCapacity());
        assertThrows(LlmRateLimiterService.LlmRateLimitExceededException.class, limiter::acquireOrThrow);
    }
}
