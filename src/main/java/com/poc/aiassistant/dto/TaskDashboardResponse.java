package com.poc.aiassistant.dto;

import java.util.List;

public record TaskDashboardResponse(
        long actionableEmails,
        List<TaskAssigneeCountDto> tasksByAssignee,
        List<EmployeeWorkloadDto> employeeWorkload,
        long overdueCount,
        long highPriorityCount,
        List<TaskDashboardTaskDto> attentionItems,
        List<TaskDashboardTaskDto> upcomingDeadlines,
        List<TaskDashboardTaskDto> recentActivity,
        List<TaskTrendPointDto> taskTrend
) {
}
