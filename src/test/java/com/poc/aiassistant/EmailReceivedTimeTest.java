package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
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
import com.poc.aiassistant.service.GraphApplicationTokenService;
import com.poc.aiassistant.service.GraphEmailService;
import com.poc.aiassistant.service.LlmTaskExtractionService;
import com.poc.aiassistant.service.OneDriveExcelService;
import com.poc.aiassistant.service.TaskPersistenceService;

/**
 * Email Received Time must come from Microsoft Graph's
 * receivedDateTime, not processing/creation/Excel-insertion time,
 * and must survive a malformed value without blocking task creation.
 */
class EmailReceivedTimeTest {

    private static EmailTaskService newService(
            TaskRepository tasks,
            TaskPersistenceService persistence
    ) {
        GraphEmailService graph = mock(GraphEmailService.class);
        LlmTaskExtractionService llm = mock(LlmTaskExtractionService.class);
        OneDriveExcelService excel = mock(OneDriveExcelService.class);
        return new EmailTaskService(graph, llm, tasks, excel, persistence);
    }

    @SuppressWarnings("unchecked")
    private static void stubPersistenceToBuildAndSave(
            TaskPersistenceService persistence,
            TaskRepository tasks
    ) {
        when(persistence.persistExactMatchAware(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<Task> supplier = invocation.getArgument(4, Supplier.class);
                    Task saved = tasks.save(supplier.get());
                    return new TaskPersistenceService.PersistResult(saved, true);
                });
    }

    @Test
    void emailReceivedAtIsParsedFromGraphReceivedDateTime() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskPersistenceService persistence = mock(TaskPersistenceService.class);
        EmailTaskService service = newService(tasks, persistence);

        EmailDto email = new EmailDto(
                "m1", "subject", "Alice", "alice@company.com", "company.com",
                "EXTERNAL", "2026-08-27T10:15:00Z", "do work", List.of(), List.of(), "mailboxA", null
        );

        when(tasks.findBySourceEmailIdAndTaskFingerprint(any(), any()))
                .thenReturn(Optional.empty());
        when(tasks.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        stubPersistenceToBuildAndSave(persistence, tasks);

        service.persistExtractedTasksForQueue(
                email,
                List.of(new ExtractedTask("Send contract to John", "desc", null,
                        TaskPriority.MEDIUM, "Alice", "alice@company.com"))
        );

        ArgumentCaptor<Task> savedTask = ArgumentCaptor.forClass(Task.class);
        verify(tasks, org.mockito.Mockito.atLeastOnce()).save(savedTask.capture());

        assertEquals(
                OffsetDateTime.parse("2026-08-27T10:15:00Z"),
                savedTask.getValue().getEmailReceivedAt()
        );
    }

    @Test
    void malformedReceivedDateTimeDoesNotBlockTaskCreation() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskPersistenceService persistence = mock(TaskPersistenceService.class);
        EmailTaskService service = newService(tasks, persistence);

        EmailDto email = new EmailDto(
                "m1", "subject", "Alice", "alice@company.com", "company.com",
                "EXTERNAL", "not-a-real-timestamp", "do work", List.of(), List.of(), "mailboxA", null
        );

        when(tasks.findBySourceEmailIdAndTaskFingerprint(any(), any()))
                .thenReturn(Optional.empty());
        when(tasks.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        stubPersistenceToBuildAndSave(persistence, tasks);

        service.persistExtractedTasksForQueue(
                email,
                List.of(new ExtractedTask("Send contract to John", "desc", null,
                        TaskPriority.MEDIUM, "Alice", "alice@company.com"))
        );

        ArgumentCaptor<Task> savedTask = ArgumentCaptor.forClass(Task.class);
        verify(tasks, org.mockito.Mockito.atLeastOnce()).save(savedTask.capture());

        assertNull(savedTask.getValue().getEmailReceivedAt());
    }

    @Test
    void emailReceivedAtIsTheLastExcelColumnAfterFromEmail() {
        Task task = new Task();
        task.setTitle("Send contract to John");
        task.setSourceSender("alice@company.com");
        task.setEmailReceivedAt(OffsetDateTime.parse("2026-08-27T10:15:00Z"));

        OneDriveExcelService excelServiceLogicOnly =
                new OneDriveExcelServiceTestHarness();

        List<Object> row = excelServiceLogicOnly.buildRowValues(task);

        assertEquals(10, row.size());
        assertEquals("alice@company.com", row.get(8));
        assertEquals("2026-08-27T10:15Z", row.get(9));
    }

    /**
     * OneDriveExcelService's constructor requires a real
     * GraphApplicationTokenService + configured base URL/site ID.
     * buildRowValues() is a pure function with no I/O, so we only
     * need a lightweight subclass that bypasses the constructor's
     * external dependencies to exercise it directly.
     */
    private static class OneDriveExcelServiceTestHarness extends OneDriveExcelService {
        OneDriveExcelServiceTestHarness() {
            super(mock(GraphApplicationTokenService.class),
                    "https://graph.microsoft.com/v1.0", "test-site-id");
        }
    }
}
