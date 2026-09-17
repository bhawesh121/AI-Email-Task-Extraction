package com.poc.aiassistant.dto;

public record EmailSourceDto(
        String domain,
        String sourceType,
        long emailCount
) {
}