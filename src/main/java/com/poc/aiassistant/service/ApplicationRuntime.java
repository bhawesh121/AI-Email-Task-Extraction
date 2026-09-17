package com.poc.aiassistant.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/**
 * Captures the email-sync start boundary ONCE, on the very first
 * time this application has ever run, and persists it so it
 * survives restarts.
 *
 * Why this matters: if the boundary were recomputed from
 * Instant.now() on every JVM start (as it originally was), then
 * any email that arrived but failed to be fully processed before
 * a restart (crash, deploy, rate limit, transient Graph/LiteLLM
 * outage) would have a receivedDateTime BEFORE the new boundary
 * after restart, and would be silently skipped forever - even
 * though it was never successfully processed. Persisting the
 * boundary once avoids that data-loss window entirely; the
 * separate EmailProcessing COMPLETED check remains the only
 * thing that ever marks an email as "done".
 */
@Component
public class ApplicationRuntime {

    private static final Logger log =
            LoggerFactory.getLogger(ApplicationRuntime.class);

    private static final String BOUNDARY_KEY =
            "email_sync_start_boundary";

    private final Instant startedAt;

    public ApplicationRuntime(
            JdbcOperations jdbcOperations
    ) {
        this.startedAt =
                loadOrCreateBoundary(jdbcOperations);
    }

    public Instant startedAt() {
        return startedAt;
    }

    private Instant loadOrCreateBoundary(
            JdbcOperations jdbcOperations
    ) {

        Instant existing =
                readBoundary(jdbcOperations);

        if (existing != null) {

            log.info(
                    "Reusing persisted email sync boundary: {}",
                    existing
            );

            return existing;
        }

        Instant now = Instant.now();

        /*
         * ON CONFLICT DO NOTHING guards against two instances
         * racing to create the boundary on first-ever startup;
         * whichever insert wins, we re-read afterwards so every
         * instance converges on the same stored value.
         */
        jdbcOperations.update(
                "INSERT INTO application_settings " +
                        "(setting_key, setting_value) VALUES (?, ?) " +
                        "ON CONFLICT (setting_key) DO NOTHING",
                BOUNDARY_KEY,
                now.toString()
        );

        Instant boundary =
                readBoundary(jdbcOperations);

        log.info(
                "Created email sync boundary (first application run): {}",
                boundary
        );

        return boundary;
    }

    private Instant readBoundary(
            JdbcOperations jdbcOperations
    ) {

        List<String> rows =
                jdbcOperations.queryForList(
                        "SELECT setting_value FROM application_settings " +
                                "WHERE setting_key = ?",
                        String.class,
                        BOUNDARY_KEY
                );

        return rows.isEmpty()
                ? null
                : Instant.parse(rows.get(0));
    }
}