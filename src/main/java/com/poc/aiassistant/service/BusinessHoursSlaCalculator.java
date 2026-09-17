package com.poc.aiassistant.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.poc.aiassistant.config.SlaProperties;

/**
 * Calculates an SLA deadline by walking forward through configured
 * working hours/days only, never simple calendar-elapsed time.
 *
 * All policy (timezone, working days, working hours, SLA duration)
 * comes from {@link SlaProperties} — nothing here is hardcoded.
 */
@Service
public class BusinessHoursSlaCalculator {

    private final SlaProperties slaProperties;

    public BusinessHoursSlaCalculator(SlaProperties slaProperties) {
        this.slaProperties = slaProperties;
    }

    /**
     * Given the moment an SLA-eligible email was received, returns the
     * deadline after consuming {@code sla.duration-hours} worth of
     * configured working-hour minutes, correctly skipping weekends/
     * non-working days and non-working hours on working days.
     */
    public OffsetDateTime calculateDeadline(OffsetDateTime receivedAt) {
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt must not be null");
        }

        ZoneId zone = slaProperties.zoneId();
        Set<DayOfWeek> workingDays = slaProperties.workingDaySet();
        java.time.LocalTime startTime = slaProperties.startTime();
        java.time.LocalTime endTime = slaProperties.endTime();

        long remainingMinutes = slaProperties.getDurationHours() * 60L;

        ZonedDateTime cursor = receivedAt.atZoneSameInstant(zone);
        cursor = rollForwardToWorkingMoment(cursor, workingDays, startTime, endTime);

        while (remainingMinutes > 0) {
            ZonedDateTime dayEnd = cursor.toLocalDate().atTime(endTime).atZone(zone);
            long minutesLeftInDay = Duration.between(cursor, dayEnd).toMinutes();

            if (minutesLeftInDay >= remainingMinutes) {
                cursor = cursor.plusMinutes(remainingMinutes);
                remainingMinutes = 0;
            } else {
                remainingMinutes -= Math.max(0, minutesLeftInDay);
                // Move to the start of the next working day.
                cursor = cursor.toLocalDate().plusDays(1).atTime(startTime).atZone(zone);
                cursor = rollForwardToWorkingMoment(cursor, workingDays, startTime, endTime);
            }
        }

        return cursor.toOffsetDateTime();
    }

    /**
     * Moves a point in time forward to the next moment inside a
     * working day/working hours window:
     * - if it lands on a non-working day, advance to the next
     *   working day's start time.
     * - if it lands before the working-hours start on a working day,
     *   advance to that day's start time.
     * - if it lands at/after the working-hours end on a working day,
     *   advance to the next working day's start time.
     * A point already inside a working window is returned unchanged.
     */
    private ZonedDateTime rollForwardToWorkingMoment(
            ZonedDateTime point,
            Set<DayOfWeek> workingDays,
            java.time.LocalTime startTime,
            java.time.LocalTime endTime
    ) {
        ZonedDateTime candidate = point;

        while (true) {
            if (!workingDays.contains(candidate.getDayOfWeek())) {
                candidate = candidate.toLocalDate().plusDays(1).atTime(startTime).atZone(candidate.getZone());
                continue;
            }

            java.time.LocalTime timeOfDay = candidate.toLocalTime();

            if (timeOfDay.isBefore(startTime)) {
                candidate = candidate.toLocalDate().atTime(startTime).atZone(candidate.getZone());
                continue;
            }

            if (!timeOfDay.isBefore(endTime)) {
                candidate = candidate.toLocalDate().plusDays(1).atTime(startTime).atZone(candidate.getZone());
                continue;
            }

            return candidate;
        }
    }
}
