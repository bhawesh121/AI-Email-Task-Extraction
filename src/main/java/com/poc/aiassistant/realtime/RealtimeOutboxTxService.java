package com.poc.aiassistant.realtime;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.poc.aiassistant.repository.RealtimeOutboxEventRepository;

@Service
public class RealtimeOutboxTxService {

    private static final Logger log =
            LoggerFactory.getLogger(RealtimeOutboxTxService.class);

    private final JdbcOperations jdbcOperations;
    private final RealtimeOutboxEventRepository repository;

    public RealtimeOutboxTxService(
            JdbcOperations jdbcOperations,
            RealtimeOutboxEventRepository repository
    ) {
        this.jdbcOperations = jdbcOperations;
        this.repository = repository;
    }

    @Transactional
    public List<UUID> claimBatch(int batchSize) {
        List<UUID> ids = jdbcOperations.query(
                "SELECT id FROM realtime_event_outbox "
                        + "WHERE status = 'PENDING' "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP) "
                        + "ORDER BY created_at, id "
                        + "FOR UPDATE SKIP LOCKED LIMIT ?",
                (ResultSet rs, int rowNum) -> rs.getObject("id", UUID.class),
                batchSize
        );

        if (ids.isEmpty()) {
            return ids;
        }

        jdbcOperations.update(
                "UPDATE realtime_event_outbox "
                        + "SET status = 'PROCESSING', claimed_at = CURRENT_TIMESTAMP, "
                        + "attempt_count = attempt_count + 1 "
                        + "WHERE id = ANY (?)",
                (PreparedStatement ps) -> {
                    Array array = ps.getConnection().createArrayOf(
                            "uuid",
                            ids.toArray()
                    );
                    ps.setArray(1, array);
                }
        );

        return ids;
    }

    @Transactional
    public void recoverStaleClaims(int staleClaimMinutes) {
        jdbcOperations.update(
                "UPDATE realtime_event_outbox "
                        + "SET status = 'PENDING', claimed_at = NULL, "
                        + "next_attempt_at = CURRENT_TIMESTAMP "
                        + "WHERE status = 'PROCESSING' "
                        + "AND claimed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 minute')",
                staleClaimMinutes
        );
    }

    @Transactional
    public void markPublished(UUID id) {
        RealtimeOutboxEvent event = repository.findById(id).orElse(null);
        if (event == null) {
            return;
        }

        event.setStatus(RealtimeOutboxStatus.PUBLISHED);
        event.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        event.setClaimedAt(null);
        event.setLastError(null);
        repository.save(event);
    }

    @Transactional
    public void markForRetry(
            RealtimeOutboxEvent event,
            Exception exception,
            int retryBaseSeconds,
            int retryMaxSeconds
    ) {
        int attempt = Math.max(1, event.getAttemptCount());
        long delay = Math.min(
                retryMaxSeconds,
                retryBaseSeconds * (1L << Math.min(attempt - 1, 10))
        );

        event.setStatus(RealtimeOutboxStatus.PENDING);
        event.setClaimedAt(null);
        event.setNextAttemptAt(
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(delay)
        );
        event.setLastError(
                exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage()
        );
        repository.save(event);

        log.warn(
                "Realtime outbox event deferred: eventId={}, type={}, attempt={}, retryAt={}",
                event.getId(),
                event.getEventType(),
                attempt,
                event.getNextAttemptAt(),
                exception
        );
    }

    @Transactional
    public void cleanupPublished(int retentionDays) {
        jdbcOperations.update(
                "DELETE FROM realtime_event_outbox "
                        + "WHERE status = 'PUBLISHED' "
                        + "AND published_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')",
                retentionDays
        );
    }
}
