package com.poc.aiassistant.dto;

import java.util.List;

public record EmailDto(
        String id,
        String subject,
        String senderName,
        String senderEmail,
        String senderDomain,
        String sourceType,
        String receivedDateTime,
        String body,
        List<String> recipientNames,
        List<String> recipientEmails,
        String mailbox,
        // Microsoft Graph conversationId. Used by SlaService to match an
        // outbound Sent Items reply back to the incoming customer email
        // it answers. May be null for sources (manual/test) that don't
        // provide it; such emails simply never match a reply.
        String conversationId
) {
}