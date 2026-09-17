package com.poc.aiassistant.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.ExtractedTask;
import com.poc.aiassistant.dto.TaskDto;
import com.poc.aiassistant.entity.EmailProcessing;

/** @deprecated Replaced by EmailExtractionScheduler/EmailExtractionWorkerService. */
@Deprecated
public class EmailQueueProcessingScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmailQueueProcessingScheduler.class);

    private final EmailProcessingService emailProcessingService;
    private final TenantGraphEmailService tenantGraphEmailService;
    private final EmailTaskService emailTaskService;
    private final LlmRateLimiterService rateLimiterService;
    private final String workerId = UUID.randomUUID().toString();

    public EmailQueueProcessingScheduler(
            EmailProcessingService emailProcessingService,
            TenantGraphEmailService tenantGraphEmailService,
            EmailTaskService emailTaskService,
            LlmRateLimiterService rateLimiterService
    ) {
        this.emailProcessingService = emailProcessingService;
        this.tenantGraphEmailService = tenantGraphEmailService;
        this.emailTaskService = emailTaskService;
        this.rateLimiterService = rateLimiterService;
    }

    @Scheduled(fixedDelayString = "${email.processing.interval-ms:10000}")
    public void processQueue() {
        int recovered = emailProcessingService.recoverExpiredLeases();
        if (recovered > 0) {
            log.warn("Recovered expired email-processing leases: count={}", recovered);
        }

        List<String> mailboxUserIds = emailProcessingService.findReadyMailboxUserIds();
        if (mailboxUserIds.isEmpty()) {
            return;
        }

        int processed = 0;
        int failed = 0;

        for (String mailboxUserId : mailboxUserIds) {
            if (!rateLimiterService.hasCapacity()) {
                log.info("LLM rate limit exhausted; leaving queued email backlog untouched");
                break;
            }

            EmailProcessing claimed = emailProcessingService.claimNextReady(mailboxUserId, workerId);
            if (claimed == null) {
                continue;
            }

            try {
                rateLimiterService.acquireOrThrow();
            } catch (LlmRateLimiterService.LlmRateLimitExceededException exhausted) {
                emailProcessingService.releaseClaimWithoutAttempt(claimed.getId(), workerId);
                log.info("LLM capacity exhausted after claim race; released email without incrementing attempt count");
                break;
            }

            try {
                emailProcessingService.markAttemptStarted(claimed.getId(), workerId);
            } catch (EmailProcessingService.LeaseOwnershipLostException staleClaim) {
                log.warn("Email claim was lost before attempt start: processingId={}, workerId={}", claimed.getId(), workerId);
                continue;
            }

            try {
                EmailDto email = tenantGraphEmailService.getMessage(
                        claimed.getMailboxUserId(),
                        claimed.getMessageId()
                );

                /*
                 * Perform network-bound LLM extraction outside the PostgreSQL
                 * completion transaction. The completion transaction below
                 * only persists Task rows and transitions EmailProcessing to
                 * COMPLETED atomically.
                 */
                com.poc.aiassistant.dto.TaskExtractionResult intelligence =
                        emailTaskService.extractIntelligenceForQueue(email);

                List<TaskDto> tasks = emailProcessingService.completeSuccessfully(
                        claimed.getId(),
                        workerId,
                        email,
                        intelligence.tasks(),
                        intelligence.responseRequired(),
                        intelligence.actionable()
                );
                processed++;
                log.info(
                        "Email processing completed: mailboxUserId={}, messageId={}, tasksExtracted={}",
                        claimed.getMailboxUserId(), claimed.getMessageId(), tasks.size()
                );
            } catch (TenantGraphEmailService.MessageNoLongerAvailableException removed) {
                emailProcessingService.markUnavailable(claimed.getId(), workerId);
                processed++;
                log.info(
                        "Queued email became unavailable before extraction; marked skipped: mailboxUserId={}, messageId={}",
                        claimed.getMailboxUserId(), claimed.getMessageId()
                );
            } catch (EmailProcessingService.LeaseOwnershipLostException staleClaim) {
                log.warn("Email claim was lost before completion/failure handling: processingId={}, workerId={}", claimed.getId(), workerId);
            } catch (Exception exception) {
                try {
                    emailProcessingService.recordFailure(claimed.getId(), workerId, exception);
                    failed++;
                } catch (EmailProcessingService.LeaseOwnershipLostException staleClaim) {
                    log.warn("Email claim was lost while recording failure: processingId={}, workerId={}", claimed.getId(), workerId);
                }
            }
        }

        long backlog = emailProcessingService.countPending();
        log.info(
                "Email queue processing cycle complete: mailboxCandidates={}, processed={}, failed={}, pendingBacklog={}, workerId={}",
                mailboxUserIds.size(), processed, failed, backlog, workerId
        );
    }
}
