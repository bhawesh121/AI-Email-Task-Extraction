package com.poc.aiassistant.dto;

import java.time.LocalDate;

/** GET /api/sla/summary response. */
public record SlaSummaryDto(

        LocalDate date,

        long total,

        long completed,

        long waiting,

        long breached,

        Double complianceRate

) {
}