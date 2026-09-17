package com.poc.aiassistant.repository;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.poc.aiassistant.entity.EmailProcessing;
import com.poc.aiassistant.entity.EmailProcessingStatus;

public interface EmailProcessingRepository
        extends JpaRepository<EmailProcessing, Long> {

    Optional<EmailProcessing> findByMailboxUserIdAndMessageId(
            String mailboxUserId,
            String messageId
    );

    boolean existsByMailboxUserIdAndMessageId(
            String mailboxUserId,
            String messageId
    );

    long countByStatus(
            EmailProcessingStatus status
    );

    List<EmailProcessing> findByStatusOrderByUpdatedAtDesc(
            EmailProcessingStatus status
    );

    @org.springframework.data.jpa.repository.Query(
            "select e.createdAt from EmailProcessing e "
                    + "where e.createdAt >= :since "
                    + "order by e.createdAt asc"
    )
    List<java.time.OffsetDateTime> findCreatedAtSince(
            java.time.OffsetDateTime since
    );
}
