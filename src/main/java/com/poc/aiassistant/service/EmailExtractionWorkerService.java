package com.poc.aiassistant.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.ExtractedTask;
import com.poc.aiassistant.entity.EmailProcessing;
import com.poc.aiassistant.entity.EmailProcessingStatus;
import com.poc.aiassistant.repository.EmailProcessingRepository;
import com.poc.aiassistant.repository.EmailQueueDao;

/**
 * Extraction half of the ingestion pipeline. Discovery remains in
 * TenantEmailSyncService; this worker drains the durable PostgreSQL queue.
 */
@Service
public class EmailExtractionWorkerService {

    private static final Logger log =
            LoggerFactory.getLogger(EmailExtractionWorkerService.class);

    private final EmailQueueDao emailQueueDao;
    private final EmailProcessingRepository emailProcessingRepository;
    private final EmailProcessingService emailProcessingService;
    private final TenantGraphEmailService tenantGraphEmailService;
    private final EmailTaskService emailTaskService;
    private final LlmRateLimiterService rateLimiterService;
    private final String workerId = "worker-" + UUID.randomUUID();
    private final int maxAttempts;
    private final int maxDeferAttempts;

    public EmailExtractionWorkerService(
            EmailQueueDao emailQueueDao,
            EmailProcessingRepository emailProcessingRepository,
            EmailProcessingService emailProcessingService,
            TenantGraphEmailService tenantGraphEmailService,
            EmailTaskService emailTaskService,
            LlmRateLimiterService rateLimiterService,
            @org.springframework.beans.factory.annotation.Value("${email.processing.max-attempts:5}") int maxAttempts,
            @org.springframework.beans.factory.annotation.Value("${email.processing.max-defer-attempts:100}") int maxDeferAttempts
    ) {
        this.emailQueueDao = emailQueueDao;
        this.emailProcessingRepository = emailProcessingRepository;
        this.emailProcessingService = emailProcessingService;
        this.tenantGraphEmailService = tenantGraphEmailService;
        this.emailTaskService = emailTaskService;
        this.rateLimiterService = rateLimiterService;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxDeferAttempts = Math.max(1, maxDeferAttempts);
    }

    public record CycleResult(
            int claimed,
            int completed,
            int skipped,
            int rateLimited,
            int failed,
            int deferred,
            int deadLettered
    ) {
        public static CycleResult empty() {
            return new CycleResult(0, 0, 0, 0, 0, 0, 0);
        }
    }

    public CycleResult runOnce(int maxBatchSize) {
        if (maxBatchSize <= 0) {
            return CycleResult.empty();
        }

        int minuteBudget = rateLimiterService.remainingThisMinute();
        int dayBudget = rateLimiterService.remainingToday();
        int plannedBudget = Math.max(0, Math.min(minuteBudget, dayBudget));
        int claimSize = Math.min(maxBatchSize, plannedBudget);

        if (claimSize == 0) {
            return CycleResult.empty();
        }

        List<Long> ids = emailQueueDao.claimBatch(
                claimSize,
                maxAttempts,
                maxDeferAttempts,
                workerId
        );

        if (ids.isEmpty()) {
            return CycleResult.empty();
        }

        List<EmailProcessing> rows = emailProcessingRepository.findAllById(ids);
        Map<Long, EmailProcessing> byId = new HashMap<>();
        for (EmailProcessing row : rows) {
            byId.put(row.getId(), row);
        }

        int completed = 0;
        int skipped = 0;
        int rateLimited = 0;
        int failed = 0;
        int deferred = 0;
        int deadLettered = 0;

        for (Long id : ids) {
            EmailProcessing processing = byId.get(id);
            if (processing == null) {
                log.error("Claimed queue row disappeared before processing: processingId={}", id);
                continue;
            }

            try {
                rateLimiterService.acquireOrThrow();
            } catch (LlmRateLimiterService.LlmRateLimitExceededException exhausted) {
                safeReleaseClaim(processing);
                rateLimited++;
                // Release all later rows claimed in this batch as well. They
                // have not consumed an LLM slot and must remain eligible.
                for (int i = ids.indexOf(id) + 1; i < ids.size(); i++) {
                    EmailProcessing remaining = byId.get(ids.get(i));
                    if (remaining != null) {
                        safeReleaseClaim(remaining);
                    }
                }
                break;
            }

            try {
                emailProcessingService.markAttemptStarted(id, workerId);
            } catch (EmailProcessingService.LeaseOwnershipLostException ownershipLost) {
                log.warn("Claim lost before extraction started: processingId={}", id);
                continue;
            }

            try {
                EmailDto email = tenantGraphEmailService.getMessage(
                        processing.getMailboxUserId(),
                        processing.getMessageId()
                );

                // LLM call happens outside completeSuccessfully()'s short DB transaction.
                com.poc.aiassistant.dto.TaskExtractionResult intelligence =
                        emailTaskService.extractIntelligenceForQueue(email);

                List<?> tasks = emailProcessingService.completeSuccessfully(
                        id,
                        workerId,
                        email,
                        intelligence.tasks(),
                        intelligence.responseRequired(),
                        intelligence.actionable()
                );

                completed++;
                log.info(
                        "Email extraction completed: mailboxUserId={}, messageId={}, tasksExtracted={}, workerId={}",
                        processing.getMailboxUserId(),
                        processing.getMessageId(),
                        tasks.size(),
                        workerId
                );
            } catch (TenantGraphEmailService.MessageNoLongerAvailableException unavailable) {
                try {
                    emailProcessingService.markUnavailable(id, workerId);
                    skipped++;
                } catch (EmailProcessingService.LeaseOwnershipLostException ownershipLost) {
                    log.warn("Claim lost while marking unavailable: processingId={}", id);
                }
            } catch (EmailProcessingService.LeaseOwnershipLostException ownershipLost) {
                log.warn("Claim lost before completion/failure handling: processingId={}", id);
            } catch (Exception failure) {
                try {
                    emailProcessingService.recordFailure(id, workerId, failure);
                    EmailProcessingStatus resultingStatus = emailProcessingService.getStatus(
                            processing.getMailboxUserId(), processing.getMessageId());
                    if (resultingStatus == EmailProcessingStatus.DEAD_LETTER) {
                        deadLettered++;
                    } else if (resultingStatus == EmailProcessingStatus.DEFERRED) {
                        deferred++;
                    } else {
                        failed++;
                    }
                } catch (EmailProcessingService.LeaseOwnershipLostException ownershipLost) {
                    log.warn("Claim lost while recording failure: processingId={}", id);
                }
            }
        }

        CycleResult result = new CycleResult(
                ids.size(),
                completed,
                skipped,
                rateLimited,
                failed,
                deferred,
                deadLettered
        );

        log.info(
                "Email extraction cycle complete: claimed={}, completed={}, skipped={}, rateLimited={}, failed={}, deferred={}, deadLettered={}, workerId={}",
                result.claimed(),
                result.completed(),
                result.skipped(),
                result.rateLimited(),
                result.failed(),
                result.deferred(),
                result.deadLettered(),
                workerId
        );

        return result;
    }

    private void safeReleaseClaim(EmailProcessing processing) {
        try {
            emailProcessingService.releaseClaimWithoutAttempt(
                    processing.getId(),
                    workerId
            );
        } catch (EmailProcessingService.LeaseOwnershipLostException ownershipLost) {
            log.warn("Claim lost while releasing unused batch row: processingId={}", processing.getId());
        }
    }
}
