package com.poc.aiassistant.dto;

import java.util.List;

/**
 * responseRequired is produced by the SAME LLM call already made for
 * task extraction (see LlmTaskExtractionService) rather than a
 * second, dedicated call — it feeds SlaService's eligibility check
 * and is otherwise independent of {@code actionable}/tasks per the
 * SLA business requirement (an email can require a response with no
 * actionable task, or vice versa).
 */
public record TaskExtractionResult(
        boolean actionable,
        List<ExtractedTask> tasks,
        boolean responseRequired
) {
    /** Backward-compatible constructor for callers that predate responseRequired. */
    public TaskExtractionResult(boolean actionable, List<ExtractedTask> tasks) {
        this(actionable, tasks, false);
    }
}