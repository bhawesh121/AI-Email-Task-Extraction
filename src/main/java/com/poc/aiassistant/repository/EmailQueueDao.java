package com.poc.aiassistant.repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Low-level durable queue operations that require PostgreSQL row-locking
 * semantics. The current EmailProcessingService remains the source of truth
 * for lease ownership and completion/failure transitions; this DAO only adds
 * a safe, atomic batch-claim operation for extraction workers.
 */
@Repository
public class EmailQueueDao {

    private final JdbcOperations jdbcOperations;

    public EmailQueueDao(JdbcOperations jdbcOperations) {
        this.jdbcOperations = jdbcOperations;
    }

    /**
     * Atomically claim up to {@code limit} ready rows across all mailboxes.
     *
     * A row is eligible when:
     *   - it is PENDING, or FAILED with its short-retry backoff elapsed
     *     and attempt_count < maxAttempts (the ordinary transient-failure
     *     path), OR
     *   - it is DEFERRED with its defer-policy backoff elapsed and
     *     defer_count < maxDeferAttempts (the "whole LLM model group was
     *     unavailable" path — deliberately checked against a SEPARATE
     *     budget from attempt_count, per the requirement that a
     *     long-duration availability outage must not burn through the
     *     normal short-retry budget).
     *
     * FOR UPDATE SKIP LOCKED makes this safe for concurrent workers/
     * instances sharing the same PostgreSQL queue. Neither counter is
     * changed here; attempt_count is incremented only after
     * acquireOrThrow() reserves an actual LLM slot, and defer_count is
     * incremented by EmailProcessingService#recordFailure when a
     * failure classifies as UNAVAILABLE.
     */
    @Transactional
    public List<Long> claimBatch(
            int limit,
            int maxAttempts,
            int maxDeferAttempts,
            String workerId
    ) {
        if (limit <= 0 || maxAttempts <= 0 || maxDeferAttempts <= 0
                || workerId == null || workerId.isBlank()) {
            return List.of();
        }

        OffsetDateTime now = OffsetDateTime.now();
        Timestamp nowTs = Timestamp.from(now.toInstant());

        return jdbcOperations.query(
                "WITH claimed AS ( " +
                        "  SELECT id FROM email_processing " +
                        "  WHERE ( " +
                        "      (status IN ('PENDING','FAILED') AND attempt_count < ?) " +
                        "      OR (status = 'DEFERRED' AND defer_count < ?) " +
                        "  ) " +
                        "    AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP) " +
                        "  ORDER BY COALESCE(next_attempt_at, created_at), created_at, id " +
                        "  LIMIT ? " +
                        "  FOR UPDATE SKIP LOCKED " +
                        ") " +
                        "UPDATE email_processing ep " +
                        "SET status='PROCESSING', claimed_at=?, claimed_by=?, " +
                        "    processed_at=NULL, error_message=NULL, updated_at=? " +
                        "FROM claimed c " +
                        "WHERE ep.id = c.id " +
                        "RETURNING ep.id",
                (rs, rowNum) -> rs.getLong("id"),
                maxAttempts,
                maxDeferAttempts,
                limit,
                nowTs,
                workerId,
                nowTs
        );
    }
}
