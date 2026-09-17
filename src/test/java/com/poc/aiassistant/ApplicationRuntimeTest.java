package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcOperations;

import com.poc.aiassistant.service.ApplicationRuntime;

class ApplicationRuntimeTest {

    @Test
    void firstStartupCreatesAndReusesPersistedBoundary() {
        JdbcOperations jdbc = Mockito.mock(JdbcOperations.class);
        String boundary = "2026-08-27T10:00:00Z";

        when(jdbc.queryForList(any(String.class), eq(String.class), any(Object[].class)))
                .thenReturn(List.of())
                .thenReturn(List.of(boundary));

        ApplicationRuntime runtime = new ApplicationRuntime(jdbc);

        assertEquals(Instant.parse(boundary), runtime.startedAt());
        verify(jdbc).update(any(String.class), eq("email_sync_start_boundary"), any(String.class));
    }

    @Test
    void restartUsesExistingBoundaryWithoutAdvancingIt() {
        JdbcOperations jdbc = Mockito.mock(JdbcOperations.class);
        String boundary = "2026-08-26T10:00:00Z";

        when(jdbc.queryForList(any(String.class), eq(String.class), any(Object[].class)))
                .thenReturn(List.of(boundary));

        ApplicationRuntime runtime = new ApplicationRuntime(jdbc);

        assertEquals(Instant.parse(boundary), runtime.startedAt());
        Mockito.verify(jdbc, Mockito.never())
                .update(any(String.class), eq("email_sync_start_boundary"), any(String.class));
    }
}
