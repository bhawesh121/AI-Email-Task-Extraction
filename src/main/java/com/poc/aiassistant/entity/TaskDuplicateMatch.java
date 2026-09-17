package com.poc.aiassistant.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Append-only audit record of one duplicate-detection decision.
 *
 * Never modifies the matched Task's own creation information — this
 * exists specifically so that duplicate-detection auditability does
 * not require touching the original task row at all.
 */
@Entity
@Table(name = "task_duplicate_matches")
public class TaskDuplicateMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "matched_task_id", nullable = false)
    private UUID matchedTaskId;

    @Column(name = "new_task_id")
    private UUID newTaskId;

    @Column(name = "source_email_id", nullable = false, length = 255)
    private String sourceEmailId;

    @Column(name = "source_mailbox", length = 320)
    private String sourceMailbox;

    @Column(name = "normalized_sender", length = 320)
    private String normalizedSender;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 30)
    private TaskDuplicateMatchType matchType;

    @Column(name = "match_reason", length = 1000)
    private String matchReason;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMatchedTaskId() {
        return matchedTaskId;
    }

    public void setMatchedTaskId(UUID matchedTaskId) {
        this.matchedTaskId = matchedTaskId;
    }

    public UUID getNewTaskId() {
        return newTaskId;
    }

    public void setNewTaskId(UUID newTaskId) {
        this.newTaskId = newTaskId;
    }

    public String getSourceEmailId() {
        return sourceEmailId;
    }

    public void setSourceEmailId(String sourceEmailId) {
        this.sourceEmailId = sourceEmailId;
    }

    public String getSourceMailbox() {
        return sourceMailbox;
    }

    public void setSourceMailbox(String sourceMailbox) {
        this.sourceMailbox = sourceMailbox;
    }

    public String getNormalizedSender() {
        return normalizedSender;
    }

    public void setNormalizedSender(String normalizedSender) {
        this.normalizedSender = normalizedSender;
    }

    public TaskDuplicateMatchType getMatchType() {
        return matchType;
    }

    public void setMatchType(TaskDuplicateMatchType matchType) {
        this.matchType = matchType;
    }

    public String getMatchReason() {
        return matchReason;
    }

    public void setMatchReason(String matchReason) {
        this.matchReason = matchReason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
