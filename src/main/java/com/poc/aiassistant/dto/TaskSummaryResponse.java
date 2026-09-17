package com.poc.aiassistant.dto;

public record TaskSummaryResponse(
        long total,
        long newTasks,
        long inProgress,
        long completed,
        long blocked,
        long overdue,
        long highPriority
) {
}