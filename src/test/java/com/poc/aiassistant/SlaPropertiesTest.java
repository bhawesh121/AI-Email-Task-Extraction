package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.DayOfWeek;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.poc.aiassistant.config.SlaProperties;

class SlaPropertiesTest {

    @Test
    void workingDaySet_acceptsThreeLetterDayNames() {
        SlaProperties properties = new SlaProperties();

        properties.setWorkingDays(
                java.util.List.of(
                        "MON",
                        "TUE",
                        "WED",
                        "THU",
                        "FRI"
                )
        );

        assertEquals(
                Set.of(
                        DayOfWeek.MONDAY,
                        DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY,
                        DayOfWeek.FRIDAY
                ),
                properties.workingDaySet()
        );
    }

    @Test
    void workingDaySet_acceptsFullDayNames() {
        SlaProperties properties = new SlaProperties();

        properties.setWorkingDays(
                java.util.List.of(
                        "MONDAY",
                        "TUESDAY",
                        "WEDNESDAY",
                        "THURSDAY",
                        "FRIDAY"
                )
        );

        assertEquals(
                Set.of(
                        DayOfWeek.MONDAY,
                        DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY,
                        DayOfWeek.FRIDAY
                ),
                properties.workingDaySet()
        );
    }
}