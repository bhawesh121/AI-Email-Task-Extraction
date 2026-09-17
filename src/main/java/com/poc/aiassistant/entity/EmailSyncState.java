package com.poc.aiassistant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "email_sync_state")
public class EmailSyncState {

    @Id
    @Column(name = "mailbox_user_id", length = 100)
    private String mailboxUserId;

    @Column(name = "delta_link", columnDefinition = "TEXT")
    private String deltaLink;

    @Column(name = "delta_next_link", columnDefinition = "TEXT")
    private String deltaNextLink;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

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

    public String getMailboxUserId() {
        return mailboxUserId;
    }

    public void setMailboxUserId(String mailboxUserId) {
        this.mailboxUserId = mailboxUserId;
    }

    public String getDeltaLink() {
        return deltaLink;
    }

    public void setDeltaLink(String deltaLink) {
        this.deltaLink = deltaLink;
    }

    public String getDeltaNextLink() {
        return deltaNextLink;
    }

    public void setDeltaNextLink(String deltaNextLink) {
        this.deltaNextLink = deltaNextLink;
    }
}
