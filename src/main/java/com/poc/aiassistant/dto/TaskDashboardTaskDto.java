package com.poc.aiassistant.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.poc.aiassistant.entity.TaskPriority;
import com.poc.aiassistant.entity.TaskStatus;

public record TaskDashboardTaskDto(
        UUID id,
        String title,
        String assignee,
        LocalDate dueDate,
        TaskPriority priority,
        TaskStatus status,
        OffsetDateTime updatedAt,
        String _reason
) {
    public static TaskDashboardTaskDto fromTask(TaskDto task, String reason) {
        return new TaskDashboardTaskDto(
                task.id(),
                task.title(),
                task.assignee(),
                task.dueDate(),
                task.priority(),
                task.status(),
                task.updatedAt(),
                reason
        );
    }

    public static TaskDashboardTaskDto fromTask(TaskDto task) {
        return fromTask(task, null);
    }
}
