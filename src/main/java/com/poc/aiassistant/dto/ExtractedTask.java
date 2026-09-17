package com.poc.aiassistant.dto;

import java.time.LocalDate;

import com.poc.aiassistant.entity.TaskPriority;

public record ExtractedTask(
        String task,
        String description,
        LocalDate dueDate,
        TaskPriority priority,
        String assignee,
        String assigneeEmail
) {
}