package com.poc.aiassistant.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Per-email intelligence classification.
 *
 * The table backing this entity was created by V20 with a much
 * richer schema (category, sentiment, urgency, opportunity/risk,
 * etc.) than any Java code has populated so far. This feature only
 * writes the two columns SLA and Task-routing actually need today:
 * {@code responseRequired} and {@code requiresAction}. The
 * remaining columns are left nullable/unset on purpose rather than
 * guessed at — populating them meaningfully is a separate,
 * unrelated body of work (a fuller email-intelligence classifier)
 * and is out of scope here. See SlaService and the LLM prompt
 * change in LlmTaskExtractionService for how these two fields are
 * produced, from the SAME extraction call already made for tasks
 * (no additional LLM call).
 */
@Entity
@Table(
        name = "email_intelligence",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_email_intelligence_mailbox_message",
                columnNames = {"mailbox", "message_id"}
        )
)
public class EmailIntelligence {

    // V20 defines this column as "UUID PRIMARY KEY" with no DEFAULT
    // clause (unlike the tables added in V23), so the id is assigned
    // here rather than relying on @GeneratedValue/a DB default.
    @Id
    private UUID id;

    @Column(name = "mailbox", nullable = false, length = 320)
    private String mailbox;

    @Column(name = "message_id", nullable = false, length = 500)
    private String messageId;

    @Column(name = "conversation_id", length = 500)
    private String conversationId;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "sender_name")
    private String senderName;

    @Column(name = "sender_email")
    private String senderEmail;

    @Column(name = "sender_domain")
    private String senderDomain;

    @Column(name = "subject")
    private String subject;

    @Column(name = "response_required", nullable = false)
    private boolean responseRequired;

    @Column(name = "requires_action", nullable = false)
    private boolean requiresAction;

    @Column(name = "analysis_status", nullable = false, length = 30)
    private String analysisStatus = "COMPLETED";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected EmailIntelligence() {
    }

    public EmailIntelligence(
            String mailbox,
            String messageId,
            String conversationId,
            OffsetDateTime receivedAt,
            String senderName,
            String senderEmail,
            String senderDomain,
            String subject,
            boolean responseRequired,
            boolean requiresAction
    ) {
        this.id = UUID.randomUUID();
        this.mailbox = mailbox;
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.receivedAt = receivedAt;
        this.senderName = senderName;
        this.senderEmail = senderEmail;
        this.senderDomain = senderDomain;
        this.subject = subject;
        this.responseRequired = responseRequired;
        this.requiresAction = requiresAction;
        this.analysisStatus = "COMPLETED";
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

    public UUID getId() {
        return id;
    }

    public String getMailbox() {
        return mailbox;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public OffsetDateTime getReceivedAt() {
        return receivedAt;
    }

    public boolean isResponseRequired() {
        return responseRequired;
    }

    public boolean isRequiresAction() {
        return requiresAction;
    }

    public String getAnalysisStatus() {
        return analysisStatus;
    }
}
