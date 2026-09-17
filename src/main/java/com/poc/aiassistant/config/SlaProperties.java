package com.poc.aiassistant.config;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Centralized, externally-configurable SLA policy.
 *
 * Nothing in BusinessHoursSlaCalculator or SlaService hardcodes
 * working days/hours, timezone, or the SLA duration — all of it is
 * bound here from application.yaml (sla.*), so operations can change
 * the policy without touching business logic.
 */
@Component
@ConfigurationProperties(prefix = "sla")
public class SlaProperties {

    /** Business-hour SLA duration, e.g. 24. */
    private int durationHours = 24;

    /** IANA timezone the working-hours window is defined in. */
    private String timezone = "UTC";

    /** Working days, e.g. MON,TUE,WED,THU,FRI. */
    private List<String> workingDays = List.of("MON", "TUE", "WED", "THU", "FRI");

    /** Working-hours start, e.g. 09:00. */
    private String workingHoursStart = "09:00";

    /** Working-hours end, e.g. 18:00. */
    private String workingHoursEnd = "18:00";

    public int getDurationHours() {
        return durationHours;
    }

    public void setDurationHours(int durationHours) {
        this.durationHours = durationHours;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public List<String> getWorkingDays() {
        return workingDays;
    }

    public void setWorkingDays(List<String> workingDays) {
        this.workingDays = workingDays;
    }

    public String getWorkingHoursStart() {
        return workingHoursStart;
    }

    public void setWorkingHoursStart(String workingHoursStart) {
        this.workingHoursStart = workingHoursStart;
    }

    public String getWorkingHoursEnd() {
        return workingHoursEnd;
    }

    public void setWorkingHoursEnd(String workingHoursEnd) {
        this.workingHoursEnd = workingHoursEnd;
    }

    public ZoneId zoneId() {
        return ZoneId.of(timezone);
    }

    public LocalTime startTime() {
        return LocalTime.parse(workingHoursStart);
    }

    public LocalTime endTime() {
        return LocalTime.parse(workingHoursEnd);
    }

    public Set<DayOfWeek> workingDaySet() {
        return workingDays.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(this::parseDayOfWeek)
                .collect(Collectors.toSet());
    }

    private DayOfWeek parseDayOfWeek(String value) {
        String normalized = value.toUpperCase();

        return switch (normalized) {
            case "MON", "MONDAY" -> DayOfWeek.MONDAY;
            case "TUE", "TUESDAY" -> DayOfWeek.TUESDAY;
            case "WED", "WEDNESDAY" -> DayOfWeek.WEDNESDAY;
            case "THU", "THUR", "THURSDAY" -> DayOfWeek.THURSDAY;
            case "FRI", "FRIDAY" -> DayOfWeek.FRIDAY;
            case "SAT", "SATURDAY" -> DayOfWeek.SATURDAY;
            case "SUN", "SUNDAY" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException(
                    "Unsupported SLA working day: " + value
            );
        };
    }
}
