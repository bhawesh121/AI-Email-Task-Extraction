package com.poc.aiassistant.dto;

import java.time.LocalDate;

/** One point of GET /api/sla/trend. */
public record SlaTrendPointDto(
        LocalDate date,
        long total,
        long completed,
        long waiting,
        long breached
) {
}
