package com.poc.aiassistant.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.poc.aiassistant.entity.EmailSyncState;

public interface EmailSyncStateRepository
        extends JpaRepository<EmailSyncState, String> {
}
