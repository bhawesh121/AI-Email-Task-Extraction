package com.poc.aiassistant.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.poc.aiassistant.entity.EmailSentSyncState;

public interface EmailSentSyncStateRepository extends JpaRepository<EmailSentSyncState, String> {
}
