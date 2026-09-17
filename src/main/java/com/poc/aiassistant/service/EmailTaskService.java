package com.poc.aiassistant.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.EmailMessageDto;
import com.poc.aiassistant.dto.ExtractedTask;
import com.poc.aiassistant.dto.TaskDto;
import com.poc.aiassistant.entity.EmailSourceType;
import com.poc.aiassistant.entity.Task;
import com.poc.aiassistant.repository.TaskRepository;
import com.poc.aiassistant.util.SenderNormalizer;

@Service
public class EmailTaskService {

    private final GraphEmailService graphEmailService;
    private final LlmTaskExtractionService llmTaskExtractionService;
    private final TaskRepository taskRepository;
    private final OneDriveExcelService oneDriveExcelService;
    private final TaskPersistenceService taskPersistenceService;

    public EmailTaskService(
            GraphEmailService graphEmailService,
            LlmTaskExtractionService llmTaskExtractionService,
            TaskRepository taskRepository,
            OneDriveExcelService oneDriveExcelService,
            TaskPersistenceService taskPersistenceService
    ) {

        this.graphEmailService =
                graphEmailService;

        this.llmTaskExtractionService =
                llmTaskExtractionService;

        this.taskRepository =
                taskRepository;

        this.oneDriveExcelService =
                oneDriveExcelService;

        this.taskPersistenceService =
                taskPersistenceService;
    }

    /**
     * Process a list of emails.
     *
     * One email can contain multiple actionable tasks.
     */
    public List<TaskDto> processInbox(
            List<EmailDto> emails
    ) {

        if (emails == null || emails.isEmpty()) {
            return List.of();
        }

        return emails.stream()
                .flatMap(email ->
                        processEmail(email).stream()
                )
                .toList();
    }

    /**
     * Fetch inbox emails from Microsoft Graph
     * and process them.
     */
    public List<TaskDto> processInboxEmails() {

        List<EmailDto> emails =
                graphEmailService.getInboxEmails();

        return processInbox(emails);
    }

    /**
     * Process one Graph email.
     *
     * Flow:
     *
     * Email
     *   ↓
     * LLM
     *   ↓
     * Multiple extracted tasks
     *   ↓
     * Fingerprint each task
     *   ↓
     * Check duplicate
     *   ↓
     * Save new task
     *   ↓
     * Export new task to Excel
     */
    public List<TaskDto> processEmail(
            EmailDto email
    ) {
        return processEmailInternal(email, true);
    }

    /** Queue path: perform only the LLM extraction. No PostgreSQL or Excel writes occur here. */
    public List<ExtractedTask> extractTasksForQueue(EmailDto email) {
        if (email == null) {
            return List.of();
        }

        if (isBlank(email.subject()) && isBlank(email.body())) {
            return List.of();
        }

        return llmTaskExtractionService.extractTasksWithoutRateLimit(email);
    }

    /**
     * Queue path: same LLM call as {@link #extractTasksForQueue(EmailDto)},
     * but also surfaces {@code responseRequired} for SLA eligibility
     * (SlaService), without a second LLM call.
     */
    public com.poc.aiassistant.dto.TaskExtractionResult extractIntelligenceForQueue(EmailDto email) {
        if (email == null) {
            return new com.poc.aiassistant.dto.TaskExtractionResult(false, List.of(), false);
        }

        if (isBlank(email.subject()) && isBlank(email.body())) {
            return new com.poc.aiassistant.dto.TaskExtractionResult(false, List.of(), false);
        }

        return llmTaskExtractionService.extractTasksAndIntelligenceWithoutRateLimit(email);
    }

    /** Queue path: persist already extracted tasks in the caller's transaction. */
    public List<TaskDto> persistExtractedTasksForQueue(
            EmailDto email,
            List<ExtractedTask> extractedTasks
    ) {
        if (email == null || extractedTasks == null || extractedTasks.isEmpty()) {
            return List.of();
        }

        return extractedTasks.stream()
                .map(extractedTask -> saveTask(email, extractedTask, false))
                .map(this::toDto)
                .toList();
    }

    /** Queue path compatibility helper for callers that still want the two steps together. */
    public List<TaskDto> processEmailForQueue(EmailDto email) {
        return persistExtractedTasksForQueue(
                email,
                extractTasksForQueue(email)
        );
    }

    private List<TaskDto> processEmailInternal(
            EmailDto email,
            boolean syncExcel
    ) {

        if (email == null) {
            return List.of();
        }

        System.out.println();
        System.out.println(
                "========================================"
        );
        System.out.println(
                "PROCESSING EMAIL"
        );
        System.out.println(
                "ID      : " + email.id()
        );
        System.out.println(
                "SUBJECT : " + email.subject()
        );
        System.out.println(
                "SENDER  : " + email.senderEmail()
        );
        System.out.println(
                "========================================"
        );

        if (isBlank(email.subject())
                && isBlank(email.body())) {

            System.out.println(
                    "Skipping email: subject and body are empty."
            );

            return List.of();
        }

        System.out.println(
                "Calling LLM task extraction..."
        );

        List<ExtractedTask> extractedTasks =
                syncExcel
                        ? llmTaskExtractionService.extractTasks(email)
                        : llmTaskExtractionService.extractTasksWithoutRateLimit(email);

        System.out.println(
                "LLM returned. Extracted tasks: "
                        + (
                        extractedTasks == null
                                ? "null"
                                : extractedTasks.size()
                )
        );

        if (extractedTasks == null
                || extractedTasks.isEmpty()) {

            System.out.println(
                    "No tasks extracted from this email."
            );

            return List.of();
        }

        System.out.println(
                "Saving extracted tasks..."
        );

        return extractedTasks.stream()
                .map(extractedTask ->
                        saveTask(
                                email,
                                extractedTask,
                                syncExcel
                        )
                )
                .map(this::toDto)
                .toList();
    }

    /**
     * Process EmailMessageDto used by the
     * manual processing endpoint.
     */
    public List<TaskDto> processEmail(
            EmailMessageDto email
    ) {

        if (email == null) {
            return List.of();
        }

        EmailDto emailDto =
                new EmailDto(
                        email.id(),
                        email.subject(),
                        email.senderName(),
                        email.senderEmail(),
                        extractDomain(
                                email.senderEmail()
                        ),
                        classifySource(
                                email.senderEmail()
                        ),
                        email.receivedDateTime() == null
                                ? null
                                : email.receivedDateTime()
                                .toString(),
                        email.bodyPreview(),
                        List.of(),
                        List.of(),
                        email.mailbox(),
                        null
                );

        return processEmail(emailDto);
    }

    /**
     * Save one extracted task.
     *
     * Multiple tasks can belong to the same email.
     *
     * Duplicate identity:
     *
     * sourceEmailId + taskFingerprint
     */
    private Task saveTask(
            EmailDto email,
            ExtractedTask extractedTask,
            boolean syncExcel
    ) {

        String taskFingerprint =
                createTaskFingerprint(
                        email,
                        extractedTask
                );

        String normalizedSender =
                SenderNormalizer.normalize(
                        email.senderEmail()
                );

        /*
         * Cheapest, most specific check first: has this exact task
         * already been extracted from this exact email? (e.g. a
         * retried queue row.) This does not need the sender-scoped
         * lock at all.
         */
        Optional<Task> existingTask =
                taskRepository
                        .findBySourceEmailIdAndTaskFingerprint(
                                email.id(),
                                taskFingerprint
                        );

        if (existingTask.isPresent()) {

            System.out.println(
                    "Skipping duplicate task (same email): "
                            + extractedTask.task()
            );

            return existingTask.get();
        }

        /*
         * Cross-email duplicate identity:
         *
         * sourceMailbox + normalizedSender + taskFingerprint
         *
         * Serialized per (mailbox, sender) via a Postgres advisory
         * lock inside TaskPersistenceService, so two concurrent
         * workers extracting the same exact task for the same
         * sender cannot both create an active Task row.
         */
        TaskPersistenceService.PersistResult result =
                taskPersistenceService.persistExactMatchAware(
                        email.id(),
                        normalizedSender,
                        email.mailbox(),
                        taskFingerprint,
                        () -> buildNewTask(
                                email,
                                extractedTask,
                                taskFingerprint,
                                normalizedSender
                        )
                );

        Task savedTask = result.task();

        if (!result.created()) {

            System.out.println(
                    "Skipping duplicate task (ALREADY_CREATED, "
                            + "active match for this sender): "
                            + extractedTask.task()
            );

            // An ALREADY_CREATED match must never produce a second
            // Excel row for the same logical task.
            return savedTask;
        }

        if (!syncExcel) {
            // Queue extraction deliberately stops at PostgreSQL. ExcelSyncRetryScheduler owns the projection.
            return savedTask;
        }

        try {
            oneDriveExcelService.appendTask(savedTask);
            savedTask.setExcelSynced(true);
            savedTask.setExcelSyncError(null);
        } catch (Exception e) {
            savedTask.setExcelSynced(false);
            savedTask.setExcelSyncError(truncate(e.getMessage(), 1000));
        }

        savedTask.setExcelSyncAttempts(savedTask.getExcelSyncAttempts() + 1);
        savedTask.setExcelLastAttemptAt(java.time.OffsetDateTime.now());
        return taskRepository.save(savedTask);
    }

    /**
     * Build (but do not persist) a new Task from one extracted task.
     * Persistence is owned by TaskPersistenceService so that the
     * sender-scoped advisory lock and the active-duplicate check
     * happen atomically with the insert.
     */
    private Task buildNewTask(
            EmailDto email,
            ExtractedTask extractedTask,
            String taskFingerprint,
            String normalizedSender
    ) {

        Task task =
                new Task();

        /*
         * AI extracted information.
         */
        task.setTitle(
                extractedTask.task()
        );

        task.setDescription(
                extractedTask.description()
        );

        task.setDueDate(
                extractedTask.dueDate()
        );

        task.setPriority(
                extractedTask.priority()
        );

        /*
         * Assignee information is resolved by
         * LlmTaskExtractionService from the
         * Microsoft Graph recipients.
         */
        task.setAssignee(
                extractedTask.assignee()
        );

        task.setAssigneeEmail(
                extractedTask.assigneeEmail()
        );

        /*
         * Task fingerprint.
         */
        task.setTaskFingerprint(
                taskFingerprint
        );

        /*
         * Source email information.
         */
        task.setSourceEmailId(
                email.id()
        );

        task.setSourceSubject(
                email.subject()
        );

        task.setSourceSender(
                email.senderEmail()
        );
        task.setSourceEmailBody(
                email.body()
        );
        task.setNormalizedSender(
                normalizedSender
        );

        task.setSourceMailbox(
                email.mailbox()
        );

        task.setSourceDomain(
                email.senderDomain()
        );

        task.setSourceType(
                parseSourceType(
                        email.sourceType()
                )
        );

        task.setEmailReceivedAt(
                parseReceivedDateTime(
                        email.receivedDateTime()
                )
        );

        System.out.println(
                "Saving new task: "
                        + extractedTask.task()
        );

        return task;
    }

    /**
     * Generate deterministic fingerprint for
     * an extracted task.
     *
     * The email ID is included so that the same
     * task appearing in different emails is not
     * treated as the same task.
     */
    /**
     * Canonical fingerprint for the task's own content — deliberately
     * does NOT include email.id(). Earlier versions did, which meant
     * two different emails could never produce the same fingerprint
     * even for byte-identical task text, silently defeating all
     * cross-email exact-duplicate detection (findActiveExactMatches /
     * the V15 unique index). Same-email retries are still correctly
     * deduplicated via the separate sourceEmailId+taskFingerprint
     * compound key in findBySourceEmailIdAndTaskFingerprint.
     */
    private String createTaskFingerprint(
            EmailDto email,
            ExtractedTask extractedTask
    ) {

        String canonicalValue =
                normalize(
                        extractedTask.task()
                )
                        + "|"
                        + normalize(
                        extractedTask.description()
                )
                        + "|"
                        + (
                        extractedTask.dueDate() == null
                                ? ""
                                : extractedTask.dueDate()
                                .toString()
                )
                        + "|"
                        + (
                        extractedTask.priority() == null
                                ? ""
                                : extractedTask.priority()
                                .name()
                )
                        + "|"
                        + normalize(
                        extractedTask.assignee()
                )
                        + "|"
                        + normalize(
                        extractedTask.assigneeEmail()
                );

        return md5(canonicalValue);
    }

    /**
     * Normalize text before fingerprint generation.
     */
    private String normalize(
            String value
    ) {

        if (value == null) {
            return "";
        }

        return value
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    /**
     * Generate MD5 fingerprint.
     *
     * This is used only for deterministic identity,
     * not for security.
     */
    private String md5(
            String value
    ) {

        try {

            MessageDigest digest =
                    MessageDigest.getInstance("MD5");

            byte[] hash =
                    digest.digest(
                            value.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            StringBuilder result =
                    new StringBuilder();

            for (byte b : hash) {

                result.append(
                        String.format(
                                "%02x",
                                b
                        )
                );
            }

            return result.toString();

        } catch (NoSuchAlgorithmException e) {

            throw new IllegalStateException(
                    "MD5 algorithm is not available",
                    e
            );
        }
    }

    /**
     * Convert Task entity to TaskDto.
     */
    private TaskDto toDto(
            Task task
    ) {

        return new TaskDto(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getAssignee(),
                task.getAssigneeEmail(),
                task.getDueDate(),
                task.getPriority(),
                task.getStatus(),
                task.getSourceEmailId(),
                task.getSourceSubject(),
                task.getSourceSender(),
                task.getSourceMailbox(),
                task.getSourceDomain(),
                task.getSourceType(),
                task.getAiReason(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getEmailReceivedAt(),
                task.getSourceEmailBody()
        );
    }
    /**
     * Safely convert source type string to enum.
     */
    private EmailSourceType parseSourceType(
            String value
    ) {

        if (isBlank(value)) {
            return null;
        }

        try {

            return EmailSourceType.valueOf(
                    value.trim().toUpperCase()
            );

        } catch (IllegalArgumentException e) {

            return null;
        }
    }

    /**
     * Parse Microsoft Graph's receivedDateTime (ISO-8601, e.g.
     * "2026-08-27T10:15:00Z") into the Task's emailReceivedAt.
     *
     * This is the actual arrival time of the email, per the business
     * requirement — distinct from processing time, task creation
     * time, and Excel insertion time, none of which are used here.
     *
     * A malformed or missing value must not block task creation:
     * this field is for audit/reporting, not correctness of the
     * dedup pipeline, so failures are logged and left null rather
     * than thrown.
     */
    private java.time.OffsetDateTime parseReceivedDateTime(
            String receivedDateTime
    ) {

        if (isBlank(receivedDateTime)) {
            return null;
        }

        try {

            return java.time.OffsetDateTime.parse(
                    receivedDateTime
            );

        } catch (java.time.format.DateTimeParseException e) {

            System.out.println(
                    "Unable to parse Graph receivedDateTime, "
                            + "leaving emailReceivedAt null: "
                            + receivedDateTime
            );

            return null;
        }
    }

    /**
     * Extract domain from an email address.
     */
    private String extractDomain(
            String email
    ) {

        if (isBlank(email)) {
            return null;
        }

        int atIndex =
                email.lastIndexOf('@');

        if (atIndex < 0
                || atIndex == email.length() - 1) {

            return null;
        }

        return email
                .substring(atIndex + 1)
                .toLowerCase();
    }

    /**
     * Classify email source.
     */
    private String classifySource(
            String senderEmail
    ) {

        if (isBlank(senderEmail)) {
            return null;
        }

        String domain =
                extractDomain(senderEmail);

        if (domain == null) {
            return null;
        }

        return domain;
    }

    /**
     * Null / blank helper.
     */
    private boolean isBlank(
            String value
    ) {

        return value == null
                || value.isBlank();
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
}