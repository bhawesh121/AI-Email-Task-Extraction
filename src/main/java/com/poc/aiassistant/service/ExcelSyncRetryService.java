package com.poc.aiassistant.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.poc.aiassistant.entity.Task;
import com.poc.aiassistant.repository.TaskRepository;

/**
 * Retries writing tasks to the shared Excel workbook when the
 * original append (done inline during email processing) failed.
 *
 * Excel is a projection of PostgreSQL data. A task is never lost
 * just because Excel was briefly unreachable or unauthorized -
 * it stays flagged excelSynced=false until this catches it up.
 */
@Service
public class ExcelSyncRetryService {

    private static final Logger log =
            LoggerFactory.getLogger(ExcelSyncRetryService.class);

    private final TaskRepository taskRepository;
    private final OneDriveExcelService oneDriveExcelService;

    public ExcelSyncRetryService(
            TaskRepository taskRepository,
            OneDriveExcelService oneDriveExcelService
    ) {
        this.taskRepository = taskRepository;
        this.oneDriveExcelService = oneDriveExcelService;
    }

    public RetryResult retryPendingTasks() {

        List<Task> pending =
                taskRepository.findByExcelSyncedFalse();

        if (pending.isEmpty()) {
            return new RetryResult(0, 0, 0);
        }

        log.info(
                "Retrying Excel sync for {} pending task(s)",
                pending.size()
        );

        int succeeded = 0;
        int failed = 0;

        for (Task task : pending) {

            try {

                oneDriveExcelService.appendTask(task);

                task.setExcelSynced(true);
                task.setExcelSyncError(null);

                succeeded++;

            } catch (Exception e) {

                log.warn(
                        "Excel retry still failing for task {}: {}",
                        task.getId(),
                        e.getMessage()
                );

                task.setExcelSynced(false);
                task.setExcelSyncError(
                        truncate(e.getMessage(), 1000)
                );

                failed++;
            }

            task.setExcelSyncAttempts(
                    task.getExcelSyncAttempts() + 1
            );

            task.setExcelLastAttemptAt(
                    OffsetDateTime.now()
            );

            taskRepository.save(task);
        }

        return new RetryResult(
                pending.size(),
                succeeded,
                failed
        );
    }

    private String truncate(
            String value,
            int maxLength
    ) {

        if (value == null) {
            return null;
        }

        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }

    public record RetryResult(
            int attempted,
            int succeeded,
            int failed
    ) {
    }
}