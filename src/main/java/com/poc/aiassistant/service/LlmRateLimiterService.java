package com.poc.aiassistant.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Enforces a hard cap on how many requests are sent to LiteLLM,
 * to stay within a free-tier quota.
 *
 * Two independent windows are tracked:
 *
 *   - a rolling-minute window (resets every 60 seconds)
 *   - a calendar-day window (resets at midnight in the
 *     configured application timezone)
 *
 * A request is rejected if EITHER cap would be exceeded.
 *
 * This is intentionally checked before the HTTP call is made,
 * so a blocked request never reaches LiteLLM at all. Callers
 * are expected to let LlmRateLimitExceededException propagate;
 * EmailProcessingService already treats any exception from the
 * extraction pipeline as a transient failure and retries the
 * email on the next scheduled sync, so no separate retry logic
 * is needed here.
 */
@Service
public class LlmRateLimiterService {

    private static final Logger log =
            LoggerFactory.getLogger(LlmRateLimiterService.class);

    private final int maxRequestsPerMinute;
    private final int maxRequestsPerDay;
    private final ZoneId zoneId;
    private final Clock clock;

    private final Object lock = new Object();

    private Instant minuteWindowStart;
    private int minuteCount;

    private LocalDate dayWindowDate;
    private int dayCount;

    public LlmRateLimiterService(
            @Value("${litellm.rate-limit.max-requests-per-minute:20}")
            int maxRequestsPerMinute,
            @Value("${litellm.rate-limit.max-requests-per-day:50}")
            int maxRequestsPerDay,
            @Value("${user.timezone:UTC}")
            String timezone
    ) {

        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.maxRequestsPerDay = maxRequestsPerDay;
        this.zoneId = ZoneId.of(timezone);
        this.clock = Clock.system(zoneId);

        log.info(
                "LlmRateLimiterService initialized: " +
                        "maxRequestsPerMinute={}, maxRequestsPerDay={}, timezone={}",
                maxRequestsPerMinute,
                maxRequestsPerDay,
                timezone
        );
    }

    /**
     * Reserve one request slot.
     *
     * Throws {@link LlmRateLimitExceededException} if either the
     * per-minute or per-day cap has already been reached, and
     * does not consume a slot in that case.
     */

    /** Non-mutating capacity check used by the durable queue before claiming work. */
    public boolean hasCapacity() {

        synchronized (lock) {
            Instant now = Instant.now(clock);
            LocalDate today = LocalDate.now(clock);
            resetWindowsIfNeeded(now, today);
            return dayCount < maxRequestsPerDay
                    && minuteCount < maxRequestsPerMinute;
        }
    }

    public void acquireOrThrow() {

        synchronized (lock) {

            Instant now = Instant.now(clock);
            LocalDate today = LocalDate.now(clock);

            resetWindowsIfNeeded(now, today);

            if (dayCount >= maxRequestsPerDay) {

                throw new LlmRateLimitExceededException(
                        "Daily LiteLLM request limit reached ("
                                + maxRequestsPerDay
                                + " requests). Resets at midnight ("
                                + zoneId
                                + ")."
                );
            }

            if (minuteCount >= maxRequestsPerMinute) {

                throw new LlmRateLimitExceededException(
                        "Per-minute LiteLLM request limit reached ("
                                + maxRequestsPerMinute
                                + " requests). Try again shortly."
                );
            }

            minuteCount++;
            dayCount++;

            log.debug(
                    "LiteLLM request permitted. minuteCount={}/{}, dayCount={}/{}",
                    minuteCount,
                    maxRequestsPerMinute,
                    dayCount,
                    maxRequestsPerDay
            );
        }
    }

    /**
     * Returns the number of request slots still available in the current
     * rolling minute without consuming a slot. This is only a planning hint
     * for queue batch sizing; acquireOrThrow() remains the authoritative
     * reservation/check immediately before an LLM request.
     */
    public int remainingThisMinute() {
        synchronized (lock) {
            Instant now = Instant.now(clock);
            LocalDate today = LocalDate.now(clock);
            resetWindowsIfNeeded(now, today);
            return Math.max(0, maxRequestsPerMinute - minuteCount);
        }
    }

    /**
     * Returns the number of request slots still available in the current
     * calendar day without consuming a slot. This is only a planning hint
     * for queue batch sizing; acquireOrThrow() remains authoritative.
     */
    public int remainingToday() {
        synchronized (lock) {
            Instant now = Instant.now(clock);
            LocalDate today = LocalDate.now(clock);
            resetWindowsIfNeeded(now, today);
            return Math.max(0, maxRequestsPerDay - dayCount);
        }
    }

    private void resetWindowsIfNeeded(
            Instant now,
            LocalDate today
    ) {

        if (minuteWindowStart == null
                || now.isAfter(
                        minuteWindowStart.plusSeconds(60)
                )) {

            minuteWindowStart = now;
            minuteCount = 0;
        }

        if (dayWindowDate == null
                || !dayWindowDate.equals(today)) {

            dayWindowDate = today;
            dayCount = 0;
        }
    }

    /**
     * Thrown when a LiteLLM request is blocked by the
     * configured rate limit. Callers should treat this the
     * same as any other extraction failure: the email is left
     * unprocessed and will be retried on the next sync cycle.
     */
    public static class LlmRateLimitExceededException
            extends RuntimeException {

        public LlmRateLimitExceededException(String message) {
            super(message);
        }
    }
}