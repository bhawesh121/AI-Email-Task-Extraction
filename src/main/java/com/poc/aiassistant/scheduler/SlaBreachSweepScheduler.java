package com.poc.aiassistant.scheduler;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.poc.aiassistant.service.SlaService;

/**
 * Periodically flips WAITING SLA rows whose deadline has passed to
 * BREACHED, so dashboard/reporting reads stay cheap (status is
 * precomputed) without ever needing a reply to have arrived.
 *
 * A reply arriving is handled immediately and separately by
 * SlaService.recordReply (via TenantSentItemsSyncService), not by
 * this sweep.
 */
@Component
public class SlaBreachSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlaBreachSweepScheduler.class);

    private final SlaService slaService;

    public SlaBreachSweepScheduler(SlaService slaService) {
        this.slaService = slaService;
    }

    @Scheduled(fixedDelayString = "${sla.breach-sweep.fixed-delay:300000}")
    public void sweepBreaches() {
        try {
            int breached = slaService.sweepBreaches(OffsetDateTime.now(ZoneOffset.UTC));
            if (breached > 0) {
                log.info("SLA breach sweep flagged {} email(s) as BREACHED", breached);
            }
        } catch (Exception exception) {
            log.error("SLA breach sweep failed", exception);
        }
    }
}
