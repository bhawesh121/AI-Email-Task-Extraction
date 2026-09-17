package com.poc.aiassistant.dto;

public record DashboardSummaryDto(
        int totalEmails,
        int totalTasks,
        int pendingTasks,
        int overdueTasks,
        int connectedMailboxes
) {
}