package com.poc.aiassistant.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.poc.aiassistant.entity.EmailIntelligence;

public interface EmailIntelligenceRepository extends JpaRepository<EmailIntelligence, UUID> {

    /** Idempotency check mirroring the uk_email_intelligence_mailbox_message constraint. */
    Optional<EmailIntelligence> findByMailboxAndMessageId(String mailbox, String messageId);
}
