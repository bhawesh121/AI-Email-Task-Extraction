package com.poc.aiassistant.dto;

import java.util.List;

/**
 * Paginated task list response used by the Tasks & Workload page.
 *
 * The items intentionally use the lightweight TaskListItemDto rather than
 * TaskDto so list requests do not transfer full email/task-detail payloads.
 */
public record TaskPageResponse(
        List<TaskListItemDto> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {
}
