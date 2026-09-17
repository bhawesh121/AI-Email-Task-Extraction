package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.poc.aiassistant.service.LlmRateLimiterService;

class LlmRateLimiterBatchPlanningTest {

    @Test
    void remainingBudgetIsNonMutatingUntilReservation() {
        LlmRateLimiterService limiter = new LlmRateLimiterService(3, 5, "UTC");

        assertEquals(3, limiter.remainingThisMinute());
        assertEquals(5, limiter.remainingToday());

        limiter.acquireOrThrow();

        assertEquals(2, limiter.remainingThisMinute());
        assertEquals(4, limiter.remainingToday());
    }
}
