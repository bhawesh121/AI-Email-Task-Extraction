package com.poc.aiassistant.realtime;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.poc.aiassistant.repository.RealtimeOutboxEventRepository;

@Service
public class RealtimeOutboxPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(RealtimeOutboxPublisher.class);

    private final RealtimeOutboxEventRepository repository;
    private final RealtimeSseBroadcaster broadcaster;
    private final RealtimeOutboxTxService txService;
    private final int batchSize;
    private final int retryBaseSeconds;
    private final int retryMaxSeconds;
    private final int staleClaimMinutes;
    private final int retentionDays;

    public RealtimeOutboxPublisher(
            RealtimeOutboxEventRepository repository,
            RealtimeSseBroadcaster broadcaster,
            RealtimeOutboxTxService txService,
            @Value("${realtime.outbox.batch-size:100}") int batchSize,
            @Value("${realtime.outbox.retry-base-seconds:2}") int retryBaseSeconds,
            @Value("${realtime.outbox.retry-max-seconds:60}") int retryMaxSeconds,
            @Value("${realtime.outbox.stale-claim-minutes:2}") int staleClaimMinutes,
            @Value("${realtime.outbox.retention-days:7}") int retentionDays
    ) {
        this.repository = repository;
        this.broadcaster = broadcaster;
        this.txService = txService;
        this.batchSize = Math.max(1, batchSize);
        this.retryBaseSeconds = Math.max(1, retryBaseSeconds);
        this.retryMaxSeconds = Math.max(this.retryBaseSeconds, retryMaxSeconds);
        this.staleClaimMinutes = Math.max(1, staleClaimMinutes);
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(fixedDelayString = "${realtime.outbox.publisher-delay-ms:1000}")
    public void publishPending() {
        txService.recoverStaleClaims(staleClaimMinutes);

        List<UUID> ids = txService.claimBatch(batchSize);

        for (UUID id : ids) {
            publishOne(id);
        }
    }

    @Scheduled(fixedDelayString = "${realtime.outbox.cleanup-delay-ms:3600000}")
    public void cleanupPublished() {
        txService.cleanupPublished(retentionDays);
    }

    @Scheduled(fixedDelayString = "${realtime.outbox.heartbeat-delay-ms:20000}")
    public void heartbeat() {
        broadcaster.heartbeat();
    }

    private void publishOne(UUID id) {
        RealtimeOutboxEvent event = repository.findById(id).orElse(null);
        if (event == null || event.getStatus() != RealtimeOutboxStatus.PROCESSING) {
            return;
        }

        try {
            broadcaster.broadcast(event);
            txService.markPublished(id);
        } catch (IOException | RuntimeException exception) {
            txService.markForRetry(
                    event,
                    exception,
                    retryBaseSeconds,
                    retryMaxSeconds
            );
            log.warn(
                    "Realtime event publishing failed and was scheduled for retry: eventId={}",
                    id,
                    exception
            );
        }
    }
}
