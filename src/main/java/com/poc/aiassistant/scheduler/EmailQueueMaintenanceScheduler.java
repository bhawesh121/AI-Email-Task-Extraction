package com.poc.aiassistant.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.poc.aiassistant.entity.EmailProcessingStatus;
import com.poc.aiassistant.repository.EmailProcessingRepository;
import com.poc.aiassistant.service.EmailProcessingService;

/** Dedicated operational maintenance for the durable email queue. */
@Component
public class EmailQueueMaintenanceScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(EmailQueueMaintenanceScheduler.class);

    private final EmailProcessingService emailProcessingService;
    private final EmailProcessingRepository emailProcessingRepository;
    private final long backlogWarningThreshold;

    public EmailQueueMaintenanceScheduler(
            EmailProcessingService emailProcessingService,
            EmailProcessingRepository emailProcessingRepository,
            @Value("${email.queue.backlog-warning-threshold:200}") long backlogWarningThreshold
    ) {
        this.emailProcessingService = emailProcessingService;
        this.emailProcessingRepository = emailProcessingRepository;
        this.backlogWarningThreshold = backlogWarningThreshold;
    }

    @Scheduled(fixedDelayString = "${email.queue.reaper-fixed-delay:300000}")
    public void recoverStaleLeases() {
        try {
            int recovered = emailProcessingService.recoverExpiredLeases();
            if (recovered > 0) {
                log.warn("Recovered {} abandoned email-processing lease(s)", recovered);
            }
        } catch (Exception exception) {
            log.error("Email queue stale-lease recovery failed", exception);
        }
    }

    @Scheduled(fixedDelayString = "${email.queue.metrics-fixed-delay:300000}")
    public void reportQueueMetrics() {
        try {
            long pending = emailProcessingRepository.countByStatus(EmailProcessingStatus.PENDING);
            long processing = emailProcessingRepository.countByStatus(EmailProcessingStatus.PROCESSING);
            long failed = emailProcessingRepository.countByStatus(EmailProcessingStatus.FAILED);
            long completed = emailProcessingRepository.countByStatus(EmailProcessingStatus.COMPLETED);
            long deadLetter = emailProcessingRepository.countByStatus(EmailProcessingStatus.DEAD_LETTER);
            long skipped = emailProcessingRepository.countByStatus(EmailProcessingStatus.SKIPPED);
            long outstanding = pending + failed;

            log.info(
                    "Email queue metrics: pending={}, processing={}, failed={}, completed={}, deadLetter={}, skipped={}",
                    pending, processing, failed, completed, deadLetter, skipped
            );

            if (deadLetter > 0) {
                log.warn("Email queue contains {} DEAD_LETTER record(s)", deadLetter);
            }
            if (outstanding > backlogWarningThreshold) {
                log.warn(
                        "Email queue outstanding backlog={} exceeds warning threshold={}",
                        outstanding,
                        backlogWarningThreshold
                );
            }
        } catch (Exception exception) {
            log.error("Email queue metrics reporting failed", exception);
        }
    }
}
