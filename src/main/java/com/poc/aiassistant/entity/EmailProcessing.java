package com.poc.aiassistant.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "email_processing",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_email_processing_mailbox_message",
                        columnNames = {
                                "mailbox_user_id",
                                "message_id"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_email_processing_status_attempt",
                        columnList = "status,next_attempt_at"
                ),
                @Index(
                        name = "idx_email_processing_mailbox",
                        columnList = "mailbox_user_id"
                ),
                @Index(
                        name = "idx_email_processing_lease",
                        columnList = "status,claimed_at"
                )
        }
)
public class EmailProcessing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "message_id",
            nullable = false,
            length = 500
    )
    private String messageId;

    @Column(
            name = "mailbox_user_id",
            nullable = false,
            length = 100
    )
    private String mailboxUserId;

    @Column(
            name = "mailbox_address",
            length = 320
    )
    private String mailboxAddress;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 30
    )
    private EmailProcessingStatus status;

    @Column(
            name = "processed_at"
    )
    private OffsetDateTime processedAt;

    @Column(
            name = "error_message",
            columnDefinition = "TEXT"
    )
    private String errorMessage;

    @Column(
            name = "next_attempt_at"
    )
    private OffsetDateTime nextAttemptAt;

    @Column(
            name = "claimed_at"
    )
    private OffsetDateTime claimedAt;

    @Column(
            name = "claimed_by",
            length = 200
    )
    private String claimedBy;

    @Column(
            name = "attempt_count",
            nullable = false
    )
    private int attemptCount = 0;

    /**
     * Separate budget from attemptCount: only incremented when a
     * failure classifies as LlmFailureCategory.UNAVAILABLE (the
     * whole configured LLM model group was unavailable), never for
     * an ordinary TRANSIENT failure. See EmailProcessingService.
     */
    @Column(
            name = "defer_count",
            nullable = false
    )
    private int deferCount = 0;

    /**
     * Set the first time this row enters DEFERRED and left
     * unchanged on subsequent re-defers, so the wall-clock defer
     * horizon (max-defer-minutes) can be enforced against how long
     * the row has been stuck, not how many times it's been checked.
     * Cleared on a successful completion or a manual requeue.
     */
    @Column(
            name = "first_deferred_at"
    )
    private OffsetDateTime firstDeferredAt;

    /**
     * Structured failure attribution (TRANSIENT / UNAVAILABLE /
     * NON_RETRYABLE), independent of the free-text errorMessage, so
     * operators can distinguish "provider outage" from "bad/malformed
     * response" from "genuine data problem" without parsing text.
     */
    @Column(
            name = "failure_category",
            length = 30
    )
    private String failureCategory;

    @Column(
            name = "created_at",
            nullable = false
    )
    private OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {

        OffsetDateTime now =
                OffsetDateTime.now();

        createdAt = now;
        updatedAt = now;

        if (status == null) {
            status = EmailProcessingStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {

        updatedAt =
                OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getMailboxUserId() {
        return mailboxUserId;
    }

    public void setMailboxUserId(String mailboxUserId) {
        this.mailboxUserId = mailboxUserId;
    }

    public String getMailboxAddress() {
        return mailboxAddress;
    }

    public void setMailboxAddress(String mailboxAddress) {
        this.mailboxAddress = mailboxAddress;
    }

    public EmailProcessingStatus getStatus() {
        return status;
    }

    public void setStatus(EmailProcessingStatus status) {
        this.status = status;
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(OffsetDateTime processedAt) {
        this.processedAt = processedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public OffsetDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public OffsetDateTime getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(OffsetDateTime claimedAt) {
        this.claimedAt = claimedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public int getDeferCount() {
        return deferCount;
    }

    public void setDeferCount(int deferCount) {
        this.deferCount = deferCount;
    }

    public OffsetDateTime getFirstDeferredAt() {
        return firstDeferredAt;
    }

    public void setFirstDeferredAt(OffsetDateTime firstDeferredAt) {
        this.firstDeferredAt = firstDeferredAt;
    }

    public String getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(String failureCategory) {
        this.failureCategory = failureCategory;
    }
}