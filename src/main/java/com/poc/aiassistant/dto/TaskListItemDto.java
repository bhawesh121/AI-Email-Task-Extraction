package com.poc.aiassistant.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.poc.aiassistant.entity.TaskPriority;
import com.poc.aiassistant.entity.TaskStatus;

/**
 * Lightweight task representation used by the paginated Tasks & Workload list.
 *
 * This intentionally excludes full email/task-detail fields such as
 * sourceEmailBody. The detail endpoint remains responsible for returning
 * the complete TaskDto when a user opens a task.
 */
public record TaskListItemDto(
        UUID id,
        String title,
        String description,
        String assignee,
        LocalDate dueDate,
        TaskPriority priority,
        TaskStatus status,
        String sourceSubject,
        String sourceSender
) {
}