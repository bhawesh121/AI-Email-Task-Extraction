package com.poc.aiassistant.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.ExtractedTask;
import com.poc.aiassistant.dto.TaskDto;
import com.poc.aiassistant.entity.EmailProcessing;
import com.poc.aiassistant.entity.EmailProcessingStatus;
import com.poc.aiassistant.repository.EmailProcessingRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

@Service
public class EmailProcessingService {

    private static final Logger log = LoggerFactory.getLogger(EmailProcessingService.class);

    private final EmailProcessingRepository emailProcessingRepository;
    private final EmailTaskService emailTaskService;
    private final SlaService slaService;
    private final EntityManager entityManager;
    private final JdbcOperations jdbcOperations;
    private final int maxAttempts;
    private final Duration leaseDuration;
    private final int maxBackoffMinutes;
    private final int maxDeferAttempts;
    private final int deferredRetryIntervalMinutes;
    private final int maxDeferMinutes;

    public EmailProcessingService(
            EmailProcessingRepository emailProcessingRepository,
            EmailTaskService emailTaskService,
            SlaService slaService,
            EntityManager entityManager,
            JdbcOperations jdbcOperations,
            @Value("${email.processing.max-attempts:5}") int maxAttempts,
            @Value("${email.processing.lease-duration-minutes:15}") int leaseDurationMinutes,
            @Value("${email.processing.max-backoff-minutes:60}") int maxBackoffMinutes,
            @Value("${email.processing.max-defer-attempts:100}") int maxDeferAttempts,
            @Value("${email.processing.deferred-retry-interval-minutes:20}") int deferredRetryIntervalMinutes,
            @Value("${email.processing.max-defer-minutes:2880}") int maxDeferMinutes
    ) {
        this.emailProcessingRepository = emailProcessingRepository;
        this.emailTaskService = emailTaskService;
        this.slaService = slaService;
        this.entityManager = entityManager;
        this.jdbcOperations = jdbcOperations;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.leaseDuration = Duration.ofMinutes(Math.max(1, leaseDurationMinutes));
        this.maxBackoffMinutes = Math.max(1, maxBackoffMinutes);
        this.maxDeferAttempts = Math.max(1, maxDeferAttempts);
        this.deferredRetryIntervalMinutes = Math.max(1, deferredRetryIntervalMinutes);
        // Default: 2880 minutes = 48 hours. Deliberately a plain,
        // configurable wall-clock ceiling — NOT derived from any
        // provider's quota-reset schedule. This is the only thing
        // that eventually moves a perpetually-UNAVAILABLE email to
        // DEAD_LETTER instead of deferring it forever.
        this.maxDeferMinutes = Math.max(1, maxDeferMinutes);
    }

    /** Durable registration only. No Graph body fetch and no LLM call. */
    @Transactional
    public boolean registerPending(
            String mailboxUserId,
            String mailboxAddress,
            String messageId
    ) {
        if (isBlank(mailboxUserId) || isBlank(messageId)) {
            throw new IllegalArgumentException("mailboxUserId and messageId must not be blank");
        }

        int inserted = jdbcOperations.update(
                "INSERT INTO email_processing "
                        + "(message_id, mailbox_user_id, mailbox_address, status, attempt_count, created_at, updated_at, next_attempt_at) "
                        + "VALUES (?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
                        + "ON CONFLICT (mailbox_user_id, message_id) DO NOTHING",
                messageId,
                mailboxUserId,
                mailboxAddress
        );

        return inserted == 1;
    }

    /**
     * Claim the oldest ready message for one mailbox. FOR UPDATE SKIP LOCKED
     * prevents concurrent workers from claiming the same row.
     */
    @Transactional
    public EmailProcessing claimNextReady(
            String mailboxUserId,
            String workerId
    ) {
        List<Long> ids = jdbcOperations.query(
                "SELECT id FROM email_processing "
                        + "WHERE mailbox_user_id = ? "
                        + "AND (status = 'PENDING' OR (status = 'FAILED' AND next_attempt_at <= CURRENT_TIMESTAMP)) "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP) "
                        + "AND attempt_count < ? "
                        + "ORDER BY COALESCE(next_attempt_at, created_at), created_at, id "
                        + "FOR UPDATE SKIP LOCKED LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"),
                mailboxUserId,
                maxAttempts
        );

        if (ids.isEmpty()) {
            return null;
        }

        EmailProcessing processing = entityManager.find(EmailProcessing.class, ids.get(0));
        if (processing == null) {
            return null;
        }

        processing.setStatus(EmailProcessingStatus.PROCESSING);
        processing.setClaimedAt(OffsetDateTime.now(ZoneOffset.UTC));
        processing.setClaimedBy(workerId);
        processing.setProcessedAt(null);
        processing.setErrorMessage(null);
        emailProcessingRepository.save(processing);
        return processing;
    }

    /** Increment an attempt only once actual LLM capacity has been reserved. */
    @Transactional
    public void markAttemptStarted(Long processingId, String workerId) {
        EmailProcessing processing = getOwnedProcessing(processingId, workerId);
        processing.setAttemptCount(processing.getAttemptCount() + 1);
        emailProcessingRepository.save(processing);
    }

    /** Put a claim back without recording a failed processing attempt. */
    @Transactional
    public void releaseClaimWithoutAttempt(Long processingId, String workerId) {
        EmailProcessing processing = getOwnedProcessing(processingId, workerId);
        processing.setStatus(EmailProcessingStatus.PENDING);
        processing.setClaimedAt(null);
        processing.setClaimedBy(null);
        processing.setNextAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        processing.setErrorMessage(null);
        emailProcessingRepository.save(processing);
    }

    /**
     * Persist already-computed extraction results and mark the queue row COMPLETED
     * in one short PostgreSQL transaction. The LLM call must happen before entering
     * this transaction so the database lock is never held across network I/O.
     */
    /**
     * Backward-compatible overload: no SLA/responseRequired signal available,
     * so no SLA row is created for this email (fails closed rather than
     * guessing at eligibility). Prefer the overload below when the caller
     * has already run intelligence extraction.
     */
    @Transactional
    public List<TaskDto> completeSuccessfully(
            Long processingId,
            String workerId,
            EmailDto email,
            List<ExtractedTask> extractedTasks
    ) {
        return completeSuccessfully(
                processingId,
                workerId,
                email,
                extractedTasks,
                false,
                !extractedTasks.isEmpty()
        );
    }

    /**
     * Persist already-computed extraction results and mark the queue row COMPLETED
     * in one short PostgreSQL transaction. The LLM call must happen before entering
     * this transaction so the database lock is never held across network I/O.
     *
     * Task persistence and SLA evaluation are deliberately independent sibling
     * calls here (see SlaService) — an SLA is never derived from Task state,
     * and completing a Task never completes an SLA.
     */
    @Transactional
    public List<TaskDto> completeSuccessfully(
            Long processingId,
            String workerId,
            EmailDto email,
            List<ExtractedTask> extractedTasks,
            boolean responseRequired,
            boolean requiresAction
    ) {
        EmailProcessing processing = getOwnedProcessing(processingId, workerId);

        List<TaskDto> tasks =
                emailTaskService.persistExtractedTasksForQueue(
                        email,
                        extractedTasks
                );

        try {
            slaService.evaluateAndCreate(email, responseRequired, requiresAction);
        } catch (Exception slaFailure) {
            // SLA processing must fail independently of task persistence:
            // an SLA-eligible email should not lose its Task(s) (or block
            // queue completion) just because SLA bookkeeping hit an error.
            log.error(
                    "SLA evaluation failed for mailboxUserId={}, messageId={}; "
                            + "task persistence already succeeded and is unaffected",
                    email.mailbox(), email.id(), slaFailure
            );
        }

        processing.setStatus(EmailProcessingStatus.COMPLETED);
        processing.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        processing.setNextAttemptAt(null);
        processing.setClaimedAt(null);
        processing.setClaimedBy(null);
        processing.setErrorMessage(null);
        emailProcessingRepository.save(processing);

        return tasks == null ? List.of() : tasks;
    }

    /**
     * Classifies the failure (see {@link LlmFailureClassifier}) and
     * routes it to one of three independent outcomes:
     *
     *   - NON_RETRYABLE  -> DEAD_LETTER immediately. Retrying (on any
     *     clock) would reproduce the same failure until a human
     *     changes configuration; see the manual requeue endpoint.
     *   - UNAVAILABLE    -> DEFERRED on its own defer_count/
     *     first_deferred_at budget, completely separate from
     *     attempt_count, so a long-duration provider/model-group
     *     outage cannot burn through the normal short-retry budget
     *     and get dead-lettered in minutes. Only the configurable
     *     wall-clock max-defer-minutes horizon (not any provider's
     *     reset schedule) eventually moves it to DEAD_LETTER.
     *   - TRANSIENT      -> FAILED with the existing short
     *     exponential backoff, exactly as before this change.
     */
    @Transactional
    public void recordFailure(
            Long processingId,
            String workerId,
            Exception exception
    ) {
        EmailProcessing processing = getOwnedProcessing(processingId, workerId);
        if (processing.getStatus() != EmailProcessingStatus.PROCESSING) {
            return;
        }

        processing.setProcessedAt(null);
        processing.setClaimedAt(null);
        processing.setClaimedBy(null);
        processing.setErrorMessage(buildErrorMessage(exception));

        LlmFailureCategory category = LlmFailureClassifier.classify(exception);
        processing.setFailureCategory(category.name());

        switch (category) {

            case NON_RETRYABLE -> {
                processing.setStatus(EmailProcessingStatus.DEAD_LETTER);
                processing.setNextAttemptAt(null);
                log.error(
                        "Email moved to dead letter (non-retryable, requires config fix + "
                                + "manual requeue): processingId={}, mailboxUserId={}, messageId={}",
                        processing.getId(), processing.getMailboxUserId(), processing.getMessageId()
                );
            }

            case UNAVAILABLE -> {
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                OffsetDateTime deferredSince = processing.getFirstDeferredAt() != null
                        ? processing.getFirstDeferredAt() : now;
                boolean deferHorizonExceeded =
                        Duration.between(deferredSince, now).toMinutes() >= maxDeferMinutes;
                int defers = processing.getDeferCount() + 1;

                if (defers > maxDeferAttempts || deferHorizonExceeded) {
                    processing.setStatus(EmailProcessingStatus.DEAD_LETTER);
                    processing.setNextAttemptAt(null);
                    processing.setDeferCount(defers);
                    log.error(
                            "Email moved to dead letter after exceeding the defer horizon "
                                    + "(all configured LLM providers remained unavailable): "
                                    + "processingId={}, mailboxUserId={}, messageId={}, "
                                    + "deferCount={}, deferredSince={}",
                            processing.getId(), processing.getMailboxUserId(),
                            processing.getMessageId(), defers, deferredSince
                    );
                } else {
                    processing.setStatus(EmailProcessingStatus.DEFERRED);
                    processing.setDeferCount(defers);
                    processing.setFirstDeferredAt(deferredSince);
                    processing.setNextAttemptAt(deferredNextAttemptAt(exception));
                    log.warn(
                            "Email deferred: the configured LLM model group is currently "
                                    + "unavailable. processingId={}, mailboxUserId={}, "
                                    + "messageId={}, deferCount={}, nextAttemptAt={}",
                            processing.getId(), processing.getMailboxUserId(),
                            processing.getMessageId(), defers, processing.getNextAttemptAt()
                    );
                }
            }

            case TRANSIENT -> {
                int attempts = processing.getAttemptCount();
                if (attempts >= maxAttempts) {
                    processing.setStatus(EmailProcessingStatus.DEAD_LETTER);
                    processing.setNextAttemptAt(null);
                    log.error(
                            "Email moved to dead letter after exhausting short-retry attempts: "
                                    + "processingId={}, mailboxUserId={}, messageId={}, attempts={}",
                            processing.getId(), processing.getMailboxUserId(),
                            processing.getMessageId(), attempts
                    );
                } else {
                    processing.setStatus(EmailProcessingStatus.FAILED);
                    processing.setNextAttemptAt(nextAttemptAt(attempts));
                    log.warn(
                            "Email processing failed and will retry: processingId={}, "
                                    + "mailboxUserId={}, messageId={}, attempt={}, nextAttemptAt={}",
                            processing.getId(), processing.getMailboxUserId(),
                            processing.getMessageId(), attempts, processing.getNextAttemptAt()
                    );
                }
            }
        }

        emailProcessingRepository.save(processing);
    }

    /**
     * Manually revive a DEAD_LETTER row after whatever caused the
     * NON_RETRYABLE (or defer-horizon) failure has been fixed — e.g.
     * a config value raised, or a genuinely resolved outage. This is
     * a deliberate, human-triggered action: a NON_RETRYABLE failure
     * will not self-heal on any timer, since nothing about it changes
     * on its own.
     */
    @Transactional
    public boolean requeueDeadLetter(Long processingId) {
        EmailProcessing processing = getRequired(processingId);
        if (processing.getStatus() != EmailProcessingStatus.DEAD_LETTER) {
            return false;
        }

        processing.setStatus(EmailProcessingStatus.PENDING);
        processing.setAttemptCount(0);
        processing.setDeferCount(0);
        processing.setFirstDeferredAt(null);
        processing.setFailureCategory(null);
        processing.setErrorMessage(null);
        processing.setNextAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        processing.setClaimedAt(null);
        processing.setClaimedBy(null);
        emailProcessingRepository.save(processing);
        return true;
    }

    /**
     * Generic Retry-After-aware defer timing: uses the provider/
     * LiteLLM-supplied wait time when one is present, otherwise the
     * configured deferred-retry-interval policy. Never assumes any
     * specific provider's reset schedule.
     */
    private OffsetDateTime deferredNextAttemptAt(Exception exception) {
        Duration retryAfter = LlmFailureClassifier.extractRetryAfter(exception);
        long minutes = retryAfter != null
                ? Math.max(1, retryAfter.toMinutes())
                : deferredRetryIntervalMinutes;
        return OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(minutes);
    }

    /** Mark a claimed message skipped when Graph confirms it is no longer available. */
    @Transactional
    public void markUnavailable(Long processingId, String workerId) {
        EmailProcessing processing = getOwnedProcessing(processingId, workerId);
        processing.setStatus(EmailProcessingStatus.SKIPPED);
        processing.setProcessedAt(null);
        processing.setNextAttemptAt(null);
        processing.setClaimedAt(null);
        processing.setClaimedBy(null);
        processing.setErrorMessage("Message is no longer available in Microsoft Graph.");
        emailProcessingRepository.save(processing);
    }

    @Transactional
    public boolean markRemoved(String mailboxUserId, String messageId) {
        EmailProcessing processing = emailProcessingRepository
                .findByMailboxUserIdAndMessageId(mailboxUserId, messageId)
                .orElse(null);

        if (processing == null || processing.getStatus() == EmailProcessingStatus.COMPLETED
                || processing.getStatus() == EmailProcessingStatus.DEAD_LETTER
                || processing.getStatus() == EmailProcessingStatus.SKIPPED
                || processing.getStatus() == EmailProcessingStatus.PROCESSING) {
            return false;
        }

        processing.setStatus(EmailProcessingStatus.SKIPPED);
        processing.setNextAttemptAt(null);
        processing.setClaimedAt(null);
        processing.setClaimedBy(null);
        processing.setErrorMessage("Message was removed from the Inbox by Microsoft Graph.");
        emailProcessingRepository.save(processing);
        return true;
    }

    /** Recover abandoned leases independently of application restarts. */
    @Transactional
    public int recoverExpiredLeases() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(leaseDuration);
        return jdbcOperations.update(
                "UPDATE email_processing "
                        + "SET status=CASE WHEN attempt_count >= ? THEN 'DEAD_LETTER' ELSE 'FAILED' END, "
                        + "claimed_at=NULL, claimed_by=NULL, "
                        + "next_attempt_at=CASE WHEN attempt_count >= ? THEN NULL ELSE CURRENT_TIMESTAMP END, "
                        + "error_message=LEFT(COALESCE(error_message, '') || ' Lease expired and was reclaimed.', 4000), "
                        + "updated_at=CURRENT_TIMESTAMP "
                        + "WHERE status='PROCESSING' AND claimed_at < ?",
                maxAttempts, maxAttempts, cutoff
        );
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return emailProcessingRepository.countByStatus(EmailProcessingStatus.PENDING)
                + emailProcessingRepository.countByStatus(EmailProcessingStatus.FAILED);
    }

    @Transactional(readOnly = true)
    public List<String> findReadyMailboxUserIds() {
        return jdbcOperations.queryForList(
                "SELECT DISTINCT mailbox_user_id FROM email_processing "
                        + "WHERE (status='PENDING' OR status='FAILED') "
                        + "AND attempt_count < ? "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP) "
                        + "ORDER BY mailbox_user_id",
                String.class,
                maxAttempts
        );
    }

    @Transactional(readOnly = true)
    public boolean isCompleted(String mailboxUserId, String messageId) {
        return emailProcessingRepository.findByMailboxUserIdAndMessageId(mailboxUserId, messageId)
                .map(processing -> processing.getStatus() == EmailProcessingStatus.COMPLETED)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public EmailProcessingStatus getStatus(String mailboxUserId, String messageId) {
        return emailProcessingRepository.findByMailboxUserIdAndMessageId(mailboxUserId, messageId)
                .map(EmailProcessing::getStatus)
                .orElse(null);
    }

    private EmailProcessing getRequired(Long id) {
        EmailProcessing processing = entityManager.find(EmailProcessing.class, id);
        if (processing == null) {
            throw new IllegalStateException("EmailProcessing row not found: " + id);
        }
        return processing;
    }

    /**
     * Completion/failure operations must lock and verify ownership so a stale
     * worker cannot finish work after its lease has been reclaimed by another worker.
     */
    private EmailProcessing getOwnedProcessing(Long id, String workerId) {
        EmailProcessing processing = entityManager.find(
                EmailProcessing.class,
                id,
                LockModeType.PESSIMISTIC_WRITE
        );
        if (processing == null) {
            throw new IllegalStateException("EmailProcessing row not found: " + id);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean leaseExpired = processing.getClaimedAt() == null
                || processing.getClaimedAt().isBefore(now.minus(leaseDuration));

        if (processing.getStatus() != EmailProcessingStatus.PROCESSING
                || workerId == null
                || !workerId.equals(processing.getClaimedBy())
                || leaseExpired) {
            throw new LeaseOwnershipLostException(
                    "EmailProcessing claim is no longer owned by worker or lease expired: " + id
            );
        }
        return processing;
    }

    public static class LeaseOwnershipLostException extends RuntimeException {
        public LeaseOwnershipLostException(String message) {
            super(message);
        }
    }

    private OffsetDateTime nextAttemptAt(int attempt) {
        long baseSeconds = 30L * (1L << Math.min(Math.max(attempt - 1, 0), 10));
        long boundedSeconds = Math.min(baseSeconds, maxBackoffMinutes * 60L);
        long jitter = Math.max(1L, boundedSeconds / 5L);
        long offset = ThreadLocalRandom.current().nextLong(jitter + 1);
        return OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(boundedSeconds + offset);
    }

    private String buildErrorMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() > 4000 ? message.substring(0, 4000) : message;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}