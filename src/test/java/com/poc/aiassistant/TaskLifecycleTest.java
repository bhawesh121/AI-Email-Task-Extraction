package com.poc.aiassistant;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.poc.aiassistant.dto.TaskDto;
import com.poc.aiassistant.entity.Task;
import com.poc.aiassistant.entity.TaskStatus;
import com.poc.aiassistant.repository.EmployeeRepository;
import com.poc.aiassistant.repository.TaskRepository;
import com.poc.aiassistant.service.TaskService;

/**
 * ARCHIVED lifecycle: no dedicated archive endpoint was added, because
 * the existing generic PATCH /tasks/{id}/status endpoint already
 * accepts any TaskStatus, including ARCHIVED, with no code change
 * required. This test locks that behavior in.
 *
 * It also covers a correctness gap that only exists BECAUSE ARCHIVED
 * is now a real status: "overdue" must not include archived tasks.
 */
class TaskLifecycleTest {

    @Test
    void updateStatusToArchivedIsAllowedThroughTheExistingGenericEndpoint() {

        TaskRepository tasks = mock(TaskRepository.class);
        EmployeeRepository employees = mock(EmployeeRepository.class);

        TaskService service =
                new TaskService(tasks, employees);

        UUID id = UUID.randomUUID();

        Task task = new Task();
        task.setStatus(TaskStatus.COMPLETED);

        when(tasks.findById(id))
                .thenReturn(Optional.of(task));

        when(tasks.save(any(Task.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        TaskDto result =
                service.updateStatus(
                        id,
                        TaskStatus.ARCHIVED
                );

        assertEquals(
                TaskStatus.ARCHIVED,
                task.getStatus()
        );

        assertEquals(
                TaskStatus.ARCHIVED,
                result.status()
        );
    }

    @Test
    void overdueTasksExcludeBothCompletedAndArchived() {

        TaskRepository tasks = mock(TaskRepository.class);
        EmployeeRepository employees = mock(EmployeeRepository.class);

        TaskService service =
                new TaskService(tasks, employees);

        when(
                tasks.findByDueDateBeforeAndStatusNotIn(
                        eq(LocalDate.now()),
                        eq(
                                List.of(
                                        TaskStatus.COMPLETED,
                                        TaskStatus.ARCHIVED
                                )
                        )
                )
        ).thenReturn(List.of());

        service.getOverdueTasks();

        verify(tasks).findByDueDateBeforeAndStatusNotIn(
                LocalDate.now(),
                List.of(
                        TaskStatus.COMPLETED,
                        TaskStatus.ARCHIVED
                )
        );
    }
}