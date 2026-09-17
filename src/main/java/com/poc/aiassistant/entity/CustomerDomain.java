package com.poc.aiassistant.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A domain whose senders are treated as customers for SLA purposes.
 *
 * Deliberately a database-backed, runtime-editable allowlist rather
 * than an application.yaml list: which external domains count as
 * "customers" is operational/business data (sales/support teams add
 * and remove accounts routinely) and must be changeable without a
 * redeploy. See CustomerDomainService for the cached lookup used on
 * the email-processing hot path.
 */
@Entity
@Table(
        name = "customer_domain",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_customer_domain_domain",
                columnNames = "domain"
        )
)
public class CustomerDomain {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "domain", nullable = false, length = 255)
    private String domain;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected CustomerDomain() {
    }

    public CustomerDomain(String domain) {
        this.domain = domain;
        this.active = true;
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

    public String getDomain() {
        return domain;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
