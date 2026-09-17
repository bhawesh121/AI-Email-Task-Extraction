package com.poc.aiassistant.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.poc.aiassistant.service.EmailExtractionWorkerService;

/** Runs independently of tenant discovery and drains the durable queue. */
@Component
public class EmailExtractionScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(EmailExtractionScheduler.class);

    private final EmailExtractionWorkerService worker;
    private final int batchSize;

    public EmailExtractionScheduler(
            EmailExtractionWorkerService worker,
            @Value("${email.queue.batch-size:20}") int batchSize
    ) {
        this.worker = worker;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(
            fixedDelayString = "${email.extraction.fixed-delay:${email.processing.interval-ms:10000}}"
    )
    public void extractBacklog() {
        try {
            worker.runOnce(batchSize);
        } catch (Exception exception) {
            // Keep the scheduled chain alive; the next cycle can retry.
            log.error("Email extraction cycle failed", exception);
        }
    }
}
