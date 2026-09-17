package com.poc.aiassistant.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.poc.aiassistant.entity.EmailSourceType;
import com.poc.aiassistant.entity.TaskPriority;
import com.poc.aiassistant.entity.TaskStatus;

public record TaskDto(

        UUID id,

        String title,

        String description,

        String assignee,

        String assigneeEmail,

        LocalDate dueDate,

        TaskPriority priority,

        TaskStatus status,

        String sourceEmailId,

        String sourceSubject,

        String sourceSender,

        String sourceMailbox,

        String sourceDomain,

        EmailSourceType sourceType,

        String aiReason,

        OffsetDateTime createdAt,

        OffsetDateTime updatedAt,

        /**
         * Original Microsoft Graph receivedDateTime for the source
         * email. Null for tasks created before this field existed,
         * or for manually-created tasks with no email source.
         */
        OffsetDateTime emailReceivedAt,

        String sourceEmailBody
) {
}