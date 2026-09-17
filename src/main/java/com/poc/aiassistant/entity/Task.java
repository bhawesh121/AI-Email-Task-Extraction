package com.poc.aiassistant.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "tasks",
        indexes = {
                @Index(
                        name = "idx_task_status",
                        columnList = "status"
                ),
                @Index(
                        name = "idx_task_assignee",
                        columnList = "assignee"
                ),
                @Index(
                        name = "idx_task_due_date",
                        columnList = "due_date"
                ),
                @Index(
                        name = "idx_task_priority",
                        columnList = "priority"
                ),
                @Index(
                        name = "idx_task_source_email",
                        columnList = "source_email_id"
                ),
                @Index(
                        name = "idx_task_normalized_sender",
                        columnList = "normalized_sender"
                )
        }
)
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 255)
    private String assignee;

    @Column(name = "assignee_email", length = 320)
    private String assigneeEmail;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskPriority priority = TaskPriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status = TaskStatus.NEW;

    @Column(
            name = "source_email_id",
            length = 500
    )
    private String sourceEmailId;

    /**
     * Deterministic fingerprint used to identify
     * the same extracted task from the same email.
     *
     * Multiple tasks can therefore belong to one email.
     */
    @Column(
            name = "task_fingerprint",
            nullable = false,
            length = 32
    )
    private String taskFingerprint;

    @Column(name = "source_subject", length = 500)
    private String sourceSubject;

    @Column(name = "source_sender", length = 320)
    private String sourceSender;

    /**
     * Normalized (trimmed + lower-cased) sender address, derived from
     * sourceSender. This is the scoping key for duplicate-task lookup:
     * "Alice@Company.com" and "alice@company.com " must resolve to the
     * same value here. Nullable because manually-created tasks (see
     * TaskService#createTask) may not have a source sender at all.
     */
    @Column(name = "normalized_sender", length = 320)
    private String normalizedSender;

    @Column(name = "source_mailbox", length = 320)
    private String sourceMailbox;

    /**
     * The original Microsoft Graph receivedDateTime for the source
     * email — NOT when this application processed the email, NOT
     * when the Task row was created, NOT when it was written to
     * Excel. Nullable because it cannot be backfilled for tasks
     * created before this column existed (EmailProcessing never
     * stored the Graph timestamp either, so there is no historical
     * source to backfill from).
     */
    @Column(name = "email_received_at")
    private OffsetDateTime emailReceivedAt;

    @Column(name = "source_thread_id", length = 500)
    private String sourceThreadId;

    @Column(name = "ai_reason", columnDefinition = "TEXT")
    private String aiReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "source_domain", length = 255)
    private String sourceDomain;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 30)
    private EmailSourceType sourceType;

    /**
     * Whether this task has been successfully written to the
     * shared Excel workbook. Excel is a projection of PostgreSQL
     * data, so a task can exist here with this flag false while
     * it waits to be retried by the sync-retry scheduler.
     */
    @Column(name = "excel_synced", nullable = false)
    private boolean excelSynced = false;

    @Column(name = "excel_sync_error", length = 1000)
    private String excelSyncError;

    @Column(name = "excel_sync_attempts", nullable = false)
    private int excelSyncAttempts = 0;

    @Column(name = "excel_last_attempt_at")
    private OffsetDateTime excelLastAttemptAt;
    
    @Column(name = "source_email_body", columnDefinition = "TEXT")
    private String sourceEmailBody;
    
    @PrePersist
    protected void onCreate() {

        OffsetDateTime now =
                OffsetDateTime.now();

        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {

        updatedAt =
                OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    /**
     * Not used by the normal persistence flow (Hibernate assigns the
     * id via @GeneratedValue on insert) — exists so unit tests can
     * construct a Task with a known id without a real database, e.g.
     * to assert that an audit row references the correct task.
     */
    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public String getAssigneeEmail() {
        return assigneeEmail;
    }

    public void setAssigneeEmail(String assigneeEmail) {
        this.assigneeEmail = assigneeEmail;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        this.priority = priority;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String getSourceEmailId() {
        return sourceEmailId;
    }

    public void setSourceEmailId(String sourceEmailId) {
        this.sourceEmailId = sourceEmailId;
    }

    public String getTaskFingerprint() {
        return taskFingerprint;
    }

    public void setTaskFingerprint(
            String taskFingerprint
    ) {
        this.taskFingerprint = taskFingerprint;
    }

    public String getSourceSubject() {
        return sourceSubject;
    }

    public void setSourceSubject(String sourceSubject) {
        this.sourceSubject = sourceSubject;
    }

    public String getSourceSender() {
        return sourceSender;
    }

    public void setSourceSender(String sourceSender) {
        this.sourceSender = sourceSender;
    }

    public String getNormalizedSender() {
        return normalizedSender;
    }

    public void setNormalizedSender(String normalizedSender) {
        this.normalizedSender = normalizedSender;
    }

    public String getSourceMailbox() {
        return sourceMailbox;
    }

    public void setSourceMailbox(String sourceMailbox) {
        this.sourceMailbox = sourceMailbox;
    }

    public OffsetDateTime getEmailReceivedAt() {
        return emailReceivedAt;
    }

    public void setEmailReceivedAt(OffsetDateTime emailReceivedAt) {
        this.emailReceivedAt = emailReceivedAt;
    }

    public String getSourceThreadId() {
        return sourceThreadId;
    }

    public void setSourceThreadId(String sourceThreadId) {
        this.sourceThreadId = sourceThreadId;
    }

    public String getAiReason() {
        return aiReason;
    }

    public void setAiReason(String aiReason) {
        this.aiReason = aiReason;
    }

    public String getSourceDomain() {
        return sourceDomain;
    }

    public void setSourceDomain(String sourceDomain) {
        this.sourceDomain = sourceDomain;
    }

    public EmailSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(
            EmailSourceType sourceType
    ) {
        this.sourceType = sourceType;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public boolean isExcelSynced() {
        return excelSynced;
    }

    public void setExcelSynced(boolean excelSynced) {
        this.excelSynced = excelSynced;
    }

    public String getExcelSyncError() {
        return excelSyncError;
    }

    public void setExcelSyncError(String excelSyncError) {
        this.excelSyncError = excelSyncError;
    }

    public int getExcelSyncAttempts() {
        return excelSyncAttempts;
    }

    public void setExcelSyncAttempts(int excelSyncAttempts) {
        this.excelSyncAttempts = excelSyncAttempts;
    }

    public OffsetDateTime getExcelLastAttemptAt() {
        return excelLastAttemptAt;
    }

    public void setExcelLastAttemptAt(OffsetDateTime excelLastAttemptAt) {
        this.excelLastAttemptAt = excelLastAttemptAt;
    }

    public String getSourceEmailBody() {
        return sourceEmailBody;
    }

    public void setSourceEmailBody(String sourceEmailBody) {
        this.sourceEmailBody = sourceEmailBody;
    }
}