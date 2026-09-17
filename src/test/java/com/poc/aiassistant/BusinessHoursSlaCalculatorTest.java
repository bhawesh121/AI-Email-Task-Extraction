package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.poc.aiassistant.config.SlaProperties;
import com.poc.aiassistant.service.BusinessHoursSlaCalculator;

/**
 * Working hours fixed at Mon-Fri 09:00-18:00 UTC, SLA duration 24 business
 * hours, matching the SLA business requirement's example configuration.
 */
class BusinessHoursSlaCalculatorTest {

    private BusinessHoursSlaCalculator calculator() {
        SlaProperties props = new SlaProperties();
        props.setTimezone("UTC");
        props.setWorkingDays(List.of("MON", "TUE", "WED", "THU", "FRI"));
        props.setWorkingHoursStart("09:00");
        props.setWorkingHoursEnd("18:00");
        props.setDurationHours(24);
        return new BusinessHoursSlaCalculator(props);
    }

    @Test
    void emailReceivedDuringWorkingHours_spansExactlyThreeWorkingDays() {
        // Wed 2026-08-26 10:00 UTC. 24 business hours = 3 working days
        // (9h/day: Wed 10:00->18:00 = 8h, Thu 9h, Fri 7h = 24h) -> Fri 16:00.
        OffsetDateTime received = OffsetDateTime.parse("2026-08-26T10:00:00Z");
        OffsetDateTime deadline = calculator().calculateDeadline(received);

        assertEquals(OffsetDateTime.parse("2026-08-28T16:00:00Z"), deadline);
    }

    @Test
    void emailReceivedOutsideWorkingHours_rollsForwardToNextWorkingDayStart() {
        // Mon 2026-08-24 20:00 UTC (after hours) rolls forward to
        // Tue 09:00, then consumes 24 business hours from there.
        OffsetDateTime received = OffsetDateTime.parse("2026-08-24T20:00:00Z");
        OffsetDateTime deadline = calculator().calculateDeadline(received);

        // Tue 09:00 + 24h business time (9h/day) = Tue 9h, Wed 9h, Thu 6h -> Thu 15:00
        assertEquals(OffsetDateTime.parse("2026-08-27T15:00:00Z"), deadline);
    }

    @Test
    void emailReceivedOnWeekend_rollsForwardToMonday() {
        // Saturday 2026-08-22 -> rolls to Monday 2026-08-24 09:00.
        OffsetDateTime received = OffsetDateTime.parse("2026-08-22T12:00:00Z");
        OffsetDateTime deadline = calculator().calculateDeadline(received);

        assertEquals(OffsetDateTime.parse("2026-08-26T15:00:00Z"), deadline);
    }

    @Test
    void emailReceivedRightAtWorkingHoursStart_doesNotRollForward() {
        OffsetDateTime received = OffsetDateTime.parse("2026-08-26T09:00:00Z");
        OffsetDateTime deadline = calculator().calculateDeadline(received);

        // Wed 9h, Thu 9h, Fri 6h -> Fri 15:00
        assertEquals(OffsetDateTime.parse("2026-08-28T15:00:00Z"), deadline);
    }

    @Test
    void emailReceivedRightAtWorkingHoursEnd_rollsToNextDayThenAcrossWeekend() {
        OffsetDateTime received = OffsetDateTime.parse("2026-08-26T18:00:00Z");
        OffsetDateTime deadline = calculator().calculateDeadline(received);

        // Rolls to Thu 09:00: Thu 9h + Fri 9h = 18h, remaining 6h skips the
        // weekend entirely and lands on the following Monday.
        assertEquals(OffsetDateTime.parse("2026-08-31T15:00:00Z"), deadline);
    }
}
