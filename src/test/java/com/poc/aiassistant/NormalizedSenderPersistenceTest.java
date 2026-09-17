package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.ExtractedTask;
import com.poc.aiassistant.entity.Task;
import com.poc.aiassistant.entity.TaskPriority;
import com.poc.aiassistant.repository.TaskRepository;
import com.poc.aiassistant.service.EmailTaskService;
import com.poc.aiassistant.service.GraphEmailService;
import com.poc.aiassistant.service.LlmTaskExtractionService;
import com.poc.aiassistant.service.OneDriveExcelService;
import com.poc.aiassistant.service.TaskPersistenceService;

/**
 * Stage 1 of the semantic-idempotency work: normalizedSender must be
 * populated consistently for every newly created task, so it never
 * drifts from what V14's SQL backfill computed for historical rows.
 */
class NormalizedSenderPersistenceTest {

    @Test
    void savedTaskGetsTrimmedLowercasedNormalizedSender() {
        GraphEmailService graph = mock(GraphEmailService.class);
        LlmTaskExtractionService llm = mock(LlmTaskExtractionService.class);
        TaskRepository tasks = mock(TaskRepository.class);
        OneDriveExcelService excel = mock(OneDriveExcelService.class);
        TaskPersistenceService persistence = mock(TaskPersistenceService.class);

        EmailTaskService service = new EmailTaskService(graph, llm, tasks, excel, persistence);

        EmailDto email = new EmailDto(
                "m1", "subject", "Alice", "  Alice@Company.com  ", "company.com",
                "EXTERNAL", "2026-08-27T10:00:00Z", "do work", List.of(), List.of(), "u1", null
        );

        when(llm.extractTasksWithoutRateLimit(email)).thenReturn(
                List.of(new ExtractedTask("Send contract to John", "desc", null,
                        TaskPriority.MEDIUM, "Alice", "alice@company.com"))
        );
        when(tasks.findBySourceEmailIdAndTaskFingerprint(any(), any()))
                .thenReturn(Optional.empty());
        when(tasks.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.persistExactMatchAware(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<Task> supplier = invocation.getArgument(4, Supplier.class);
                    Task saved = tasks.save(supplier.get());
                    return new TaskPersistenceService.PersistResult(saved, true);
                });

        service.processEmailForQueue(email);

        ArgumentCaptor<Task> savedTask = ArgumentCaptor.forClass(Task.class);
        verify(tasks).save(savedTask.capture());

        assertEquals("alice@company.com", savedTask.getValue().getNormalizedSender());
        // Raw source sender is preserved as-is for display/audit purposes.
        assertEquals("  Alice@Company.com  ", savedTask.getValue().getSourceSender());
    }
}

