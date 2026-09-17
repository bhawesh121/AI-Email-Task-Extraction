package com.poc.aiassistant.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.poc.aiassistant.entity.EmailSlaStatus;

/** One row of GET /api/sla/breached. */
public record BreachedEmailDto(
        UUID id,
        String customerEmail,
        String customerDomain,
        String subject,
        OffsetDateTime receivedAt,
        OffsetDateTime slaDeadlineAt,
        OffsetDateTime firstResponseAt,
        Long delayMinutes,
        EmailSlaStatus status,
        String respondedBy
) {
}
