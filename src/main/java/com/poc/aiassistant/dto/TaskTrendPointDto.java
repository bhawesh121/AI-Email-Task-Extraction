package com.poc.aiassistant.dto;

import java.time.LocalDate;

public record TaskTrendPointDto(
        LocalDate date,
        long count
) {
}
