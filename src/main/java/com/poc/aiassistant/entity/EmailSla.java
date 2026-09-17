package com.poc.aiassistant.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Email-level SLA (response-time) tracking.
 *
 * Deliberately separate from {@link Task}. A Task being completed
 * must never mark an SLA completed; only a matched outbound reply
 * from Sent Items does that (see SlaService).
 */
@Entity
@Table(
        name = "email_sla",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_email_sla_mailbox_message",
                columnNames = {"mailbox_user_id", "message_id"}
        )
)
public class EmailSla {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "mailbox_user_id", nullable = false, length = 100)
    private String mailboxUserId;

    @Column(name = "message_id", nullable = false, length = 500)
    private String messageId;

    @Column(name = "conversation_id", length = 500)
    private String conversationId;

    @Column(name = "customer_email", nullable = false, length = 320)
    private String customerEmail;

    @Column(name = "customer_domain", nullable = false, length = 255)
    private String customerDomain;

    @Column(name = "subject", length = 500)
    private String subject;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "response_required", nullable = false)
    private boolean responseRequired;

    @Column(name = "sla_start_at", nullable = false)
    private OffsetDateTime slaStartAt;

    @Column(name = "sla_deadline_at", nullable = false)
    private OffsetDateTime slaDeadlineAt;

    @Column(name = "first_response_at")
    private OffsetDateTime firstResponseAt;

    @Column(name = "response_message_id", length = 500)
    private String responseMessageId;

    @Column(name = "responded_by", length = 320)
    private String respondedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmailSlaStatus status = EmailSlaStatus.WAITING;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected EmailSla() {
    }

    public EmailSla(
            String mailboxUserId,
            String messageId,
            String conversationId,
            String customerEmail,
            String customerDomain,
            String subject,
            OffsetDateTime receivedAt,
            OffsetDateTime slaStartAt,
            OffsetDateTime slaDeadlineAt
    ) {
        this.mailboxUserId = mailboxUserId;
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.customerEmail = customerEmail;
        this.customerDomain = customerDomain;
        this.subject = subject;
        this.receivedAt = receivedAt;
        this.responseRequired = true;
        this.slaStartAt = slaStartAt;
        this.slaDeadlineAt = slaDeadlineAt;
        this.status = EmailSlaStatus.WAITING;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /**
     * Records the first qualifying outbound reply and derives the
     * resulting status. Once BREACHED, status never moves back to
     * COMPLETED (a late reply is still recorded for reporting, but
     * does not undo the breach). Only the first reply is ever
     * recorded; subsequent calls are ignored.
     */
    public void recordFirstResponse(
            OffsetDateTime respondedAt,
            String responseMessageId,
            String respondedBy
    ) {
        if (this.firstResponseAt != null) {
            return;
        }

        this.firstResponseAt = respondedAt;
        this.responseMessageId = responseMessageId;
        this.respondedBy = respondedBy;

        if (this.status != EmailSlaStatus.BREACHED) {
            this.status = respondedAt.isAfter(this.slaDeadlineAt)
                    ? EmailSlaStatus.BREACHED
                    : EmailSlaStatus.COMPLETED;
        }
    }

    /** Applied by the periodic breach sweep for rows with no reply at all. */
    public void markBreached() {
        if (this.status == EmailSlaStatus.WAITING) {
            this.status = EmailSlaStatus.BREACHED;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getMailboxUserId() {
        return mailboxUserId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public String getCustomerDomain() {
        return customerDomain;
    }

    public String getSubject() {
        return subject;
    }

    public OffsetDateTime getReceivedAt() {
        return receivedAt;
    }

    public boolean isResponseRequired() {
        return responseRequired;
    }

    public OffsetDateTime getSlaStartAt() {
        return slaStartAt;
    }

    public OffsetDateTime getSlaDeadlineAt() {
        return slaDeadlineAt;
    }

    public OffsetDateTime getFirstResponseAt() {
        return firstResponseAt;
    }

    public String getResponseMessageId() {
        return responseMessageId;
    }

    public String getRespondedBy() {
        return respondedBy;
    }

    public EmailSlaStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
