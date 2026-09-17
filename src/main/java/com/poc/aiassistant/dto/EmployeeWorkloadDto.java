package com.poc.aiassistant.dto;

public record EmployeeWorkloadDto(
        String name,
        long open,
        long overdue,
        long highPriority,
        long total
) {
}
