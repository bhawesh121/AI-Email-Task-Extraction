package com.poc.aiassistant.dto;

import java.time.OffsetDateTime;

public record EmailMessageDto(
        String id,
        String subject,
        String senderName,
        String senderEmail,
        OffsetDateTime receivedDateTime,
        String bodyPreview,
        String mailbox
) {
}