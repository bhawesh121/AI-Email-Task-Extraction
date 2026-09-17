package com.poc.aiassistant.controller;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.poc.aiassistant.entity.EmailProcessing;
import com.poc.aiassistant.entity.EmailProcessingStatus;
import com.poc.aiassistant.repository.EmailProcessingRepository;
import com.poc.aiassistant.service.EmailProcessingService;

/**
 * Queue/admin visibility, plus the one intentionally manual recovery
 * action: requeuing a DEAD_LETTER row. Project-wide security still
 * requires authentication.
 */
@RestController
@RequestMapping("/api/admin/email-sync")
public class EmailQueueAdminController {

    private final EmailProcessingRepository repository;
    private final EmailProcessingService emailProcessingService;

    public EmailQueueAdminController(
            EmailProcessingRepository repository,
            EmailProcessingService emailProcessingService
    ) {
        this.repository = repository;
        this.emailProcessingService = emailProcessingService;
    }

    @GetMapping("/queue-stats")
    public QueueStats queueStats() {
        return new QueueStats(
                repository.countByStatus(EmailProcessingStatus.PENDING),
                repository.countByStatus(EmailProcessingStatus.PROCESSING),
                repository.countByStatus(EmailProcessingStatus.FAILED),
                repository.countByStatus(EmailProcessingStatus.DEFERRED),
                repository.countByStatus(EmailProcessingStatus.COMPLETED),
                repository.countByStatus(EmailProcessingStatus.DEAD_LETTER),
                repository.countByStatus(EmailProcessingStatus.SKIPPED)
        );
    }

    @GetMapping("/dead-letters")
    public List<QueueEntry> deadLetters() {
        return repository
                .findByStatusOrderByUpdatedAtDesc(EmailProcessingStatus.DEAD_LETTER)
                .stream()
                .map(QueueEntry::from)
                .toList();
    }

    /**
     * Visibility into currently-deferred rows — i.e. emails waiting
     * on a whole-LLM-model-group outage to clear, distinct from
     * ordinary short-retry FAILED rows and from genuinely dead
     * DEAD_LETTER rows. A sustained, growing count here is a real
     * operational signal even though (unlike DEAD_LETTER) it does
     * not require any manual action by itself.
     */
    @GetMapping("/deferred")
    public List<QueueEntry> deferred() {
        return repository
                .findByStatusOrderByUpdatedAtDesc(EmailProcessingStatus.DEFERRED)
                .stream()
                .map(QueueEntry::from)
                .toList();
    }

    /**
     * Manually revive a DEAD_LETTER row — the intended recovery path
     * for NON_RETRYABLE failures (e.g. finishReason=length caused by
     * a max-tokens value that has since been raised) and for the rare
     * case a DEFERRED row exceeded the defer horizon before a genuine
     * outage actually cleared. Deliberately requires an explicit
     * human action rather than any automatic timer, since a
     * NON_RETRYABLE failure cannot self-heal.
     */
    @PostMapping("/dead-letters/{id}/requeue")
    public ResponseEntity<Void> requeueDeadLetter(@PathVariable Long id) {
        boolean requeued = emailProcessingService.requeueDeadLetter(id);
        return requeued ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/ingestion-timestamps")
    public List<OffsetDateTime> ingestionTimestamps(
            @RequestParam(defaultValue = "30") int days
    ) {
        int safeDays = Math.max(1, Math.min(days, 365));
        OffsetDateTime since = OffsetDateTime.now().minusDays(safeDays);
        return repository.findCreatedAtSince(since);
    }

    public record QueueStats(
            long pending,
            long processing,
            long failed,
            long deferred,
            long completed,
            long deadLetter,
            long skipped
    ) {}

    /** Deliberately excludes mailbox address and message body/content. */
    public record QueueEntry(
            Long id,
            String mailboxUserId,
            String messageId,
            int attemptCount,
            int deferCount,
            String failureCategory,
            String errorMessage,
            OffsetDateTime firstDeferredAt,
            OffsetDateTime updatedAt
    ) {
        static QueueEntry from(EmailProcessing processing) {
            return new QueueEntry(
                    processing.getId(),
                    processing.getMailboxUserId(),
                    processing.getMessageId(),
                    processing.getAttemptCount(),
                    processing.getDeferCount(),
                    processing.getFailureCategory(),
                    processing.getErrorMessage(),
                    processing.getFirstDeferredAt(),
                    processing.getUpdatedAt()
            );
        }
    }
}
