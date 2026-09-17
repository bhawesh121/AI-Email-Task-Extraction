package com.poc.aiassistant.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Sent Items delta-sync cursor, one row per mailbox.
 *
 * Mirrors {@link EmailSyncState}, which tracks the Inbox delta
 * cursor. Sent Items is a different Graph mail folder with its own
 * independent delta token, so it needs its own state table rather
 * than overloading the existing one.
 */
@Entity
@Table(name = "email_sent_sync_state")
public class EmailSentSyncState {

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
