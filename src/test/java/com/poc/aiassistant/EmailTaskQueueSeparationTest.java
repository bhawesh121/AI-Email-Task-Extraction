package com.poc.aiassistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.ExtractedTask;
import com.poc.aiassistant.entity.Task;
import com.poc.aiassistant.repository.TaskRepository;
import com.poc.aiassistant.service.EmailTaskService;
import com.poc.aiassistant.service.GraphEmailService;
import com.poc.aiassistant.service.LlmTaskExtractionService;
import com.poc.aiassistant.service.OneDriveExcelService;
import com.poc.aiassistant.service.TaskPersistenceService;
import com.poc.aiassistant.entity.TaskPriority;

class EmailTaskQueueSeparationTest {

    @Test
    void queuePathDoesNotCallExcelAndCanCompleteExtractionIndependently() {
        GraphEmailService graph = mock(GraphEmailService.class);
        LlmTaskExtractionService llm = mock(LlmTaskExtractionService.class);
        TaskRepository tasks = mock(TaskRepository.class);
        OneDriveExcelService excel = mock(OneDriveExcelService.class);
        TaskPersistenceService persistence = mock(TaskPersistenceService.class);

        EmailTaskService service = new EmailTaskService(graph, llm, tasks, excel, persistence);
        EmailDto email = new EmailDto(
                "m1", "subject", "Sender", "sender@test", "test", "INTERNAL",
                "2026-08-27T10:00:00Z", "do work", List.of(), List.of(), "u1", null
        );
        when(llm.extractTasksWithoutRateLimit(email)).thenReturn(
                List.of(new ExtractedTask("Do work", "Description", null, TaskPriority.MEDIUM, "Sender", "sender@test"))
        );
        when(tasks.findBySourceEmailIdAndTaskFingerprint(any(), any()))
                .thenReturn(Optional.empty());
        when(tasks.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // Mirror TaskPersistenceService's real behavior closely enough for this
        // test's purpose: build the task via the supplied Supplier and persist
        // it through the (mocked) TaskRepository, reporting it as newly created.
        when(persistence.persistExactMatchAware(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<Task> supplier = invocation.getArgument(4, Supplier.class);
                    Task saved = tasks.save(supplier.get());
                    return new TaskPersistenceService.PersistResult(saved, true);
                });

        service.processEmailForQueue(email);

        verify(tasks).save(any(Task.class));
        verify(llm).extractTasksWithoutRateLimit(email);
        verify(llm, never()).extractTasks(email);
        verify(excel, never()).appendTask(any(Task.class));
    }
}

