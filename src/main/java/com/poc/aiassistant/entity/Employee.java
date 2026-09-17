package com.poc.aiassistant.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "employees",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_employee_email",
                        columnNames = "email"
                ),
                @UniqueConstraint(
                        name = "uk_employee_microsoft_user_id",
                        columnNames = "microsoft_user_id"
                )
        }
)
public class Employee {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    /**
     * Stable Microsoft Graph user identifier for the tenant user.
     * This is the identity used for automatic mailbox/assignee resolution.
     */
    @Column(name = "microsoft_user_id", length = 100)
    private String microsoftUserId;

    protected Employee() {
    }

    public Employee(
            String name,
            String email
    ) {
        this.name = name;
        this.email = email;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getMicrosoftUserId() {
        return microsoftUserId;
    }

    public void setMicrosoftUserId(String microsoftUserId) {
        this.microsoftUserId = microsoftUserId;
    }

    /**
     * Update employee information when the
     * Microsoft 365 tenant user changes.
     */
    public void update(
            String name,
            String email
    ) {

        if (name != null
                && !name.isBlank()) {

            this.name = name.trim();
        }

        if (email != null
                && !email.isBlank()) {

            this.email = email.trim();
        }
    }
}