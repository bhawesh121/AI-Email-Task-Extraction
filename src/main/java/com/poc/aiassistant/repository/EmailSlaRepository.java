package com.poc.aiassistant.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.poc.aiassistant.entity.EmailSla;
import com.poc.aiassistant.entity.EmailSlaStatus;

public interface EmailSlaRepository
        extends JpaRepository<EmailSla, java.util.UUID>,
        JpaSpecificationExecutor<EmailSla> {

    /**
     * Idempotency check:
     * one incoming email can never create two SLA rows.
     */
    Optional<EmailSla> findByMailboxUserIdAndMessageId(
            String mailboxUserId,
            String messageId
    );

    /**
     * Primary SLA reply-correlation mechanism.
     *
     * Finds unresolved SLA rows belonging to the same Graph
     * conversation.
     */
    List<EmailSla> findByConversationIdAndFirstResponseAtIsNull(
            String conversationId
    );

    /**
     * Fallback SLA reply-correlation mechanism.
     *
     * Searches unresolved SLA rows for the customer regardless
     * of which employee mailbox owns the SLA.
     *
     * SlaService performs the additional safety checks:
     * - sentAt must be after receivedAt
     * - customer must be among the reply recipients
     * - exactly one unique candidate must exist
     */
    List<EmailSla> findByCustomerEmailIgnoreCaseAndFirstResponseAtIsNull(
            String customerEmail
    );

    /**
     * Periodic breach sweep.
     */
    List<EmailSla> findByStatusAndSlaDeadlineAtBefore(
            EmailSlaStatus status,
            OffsetDateTime cutoff
    );

    long countByStatus(
            EmailSlaStatus status
    );

    @Query("""
            select count(e)
            from EmailSla e
            where e.receivedAt >= :from
              and e.receivedAt < :to
            """)
    long countByReceivedAtBetween(
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to
    );

    @Query("""
            select count(e)
            from EmailSla e
            where e.status = :status
              and e.receivedAt >= :from
              and e.receivedAt < :to
            """)
    long countByStatusAndReceivedAtBetween(
            @Param("status") EmailSlaStatus status,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to
    );
}