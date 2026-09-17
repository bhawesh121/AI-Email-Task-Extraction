package com.poc.aiassistant.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.poc.aiassistant.service.ExcelSyncRetryService;

@Component
public class ExcelSyncRetryScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(ExcelSyncRetryScheduler.class);

    private final ExcelSyncRetryService excelSyncRetryService;

    public ExcelSyncRetryScheduler(
            ExcelSyncRetryService excelSyncRetryService
    ) {
        this.excelSyncRetryService = excelSyncRetryService;
    }

    /**
     * Periodically retries any task that PostgreSQL has but the
     * Excel workbook does not yet have, e.g. because the Graph
     * app-only token was briefly unavailable or the workbook API
     * call failed transiently.
     */
    @Scheduled(
            fixedDelayString = "${excel.sync.retry-fixed-delay:300000}"
    )
    public void retryPendingExcelSyncs() {

        try {

            ExcelSyncRetryService.RetryResult result =
                    excelSyncRetryService.retryPendingTasks();

            if (result.attempted() > 0) {

                log.info(
                        "Excel sync retry completed: attempted={}, succeeded={}, failed={}",
                        result.attempted(),
                        result.succeeded(),
                        result.failed()
                );
            }

        } catch (Exception exception) {

            log.error(
                    "Excel sync retry run failed",
                    exception
            );
        }
    }
}