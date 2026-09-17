package com.poc.aiassistant.dto;

import java.util.List;

public record TaskFilterOptionsResponse(
        List<AssigneeOption> assignees,
        List<String> sourceDomains,
        List<String> sourceTypes
) {

    public record AssigneeOption(
            String name,
            String email
    ) {
    }
}