package com.poc.aiassistant.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.poc.aiassistant.service.TenantEmailSyncService;
import com.poc.aiassistant.service.TenantGraphEmailService;
import com.poc.aiassistant.service.TenantSentItemsSyncService;

@Component
public class TenantEmailSyncScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(TenantEmailSyncScheduler.class);

    private final TenantEmailSyncService tenantEmailSyncService;
    private final TenantSentItemsSyncService tenantSentItemsSyncService;
    private final TenantGraphEmailService tenantGraphEmailService;

    public TenantEmailSyncScheduler(
            TenantEmailSyncService tenantEmailSyncService,
            TenantSentItemsSyncService tenantSentItemsSyncService,
            TenantGraphEmailService tenantGraphEmailService
    ) {
        this.tenantEmailSyncService = tenantEmailSyncService;
        this.tenantSentItemsSyncService = tenantSentItemsSyncService;
        this.tenantGraphEmailService = tenantGraphEmailService;
    }

    /**
     * Periodically checks all tenant mailboxes
     * for new emails.
     */
    @Scheduled(
            fixedDelayString = "${email.sync.fixed-delay:60000}"
    )
    public void synchronizeTenantMailboxes() {

        log.info("Starting automatic tenant email synchronization");

        try {

            TenantEmailSyncService.TenantSyncResult result =
                    tenantEmailSyncService.syncTenant();

            log.info(
                    "Tenant synchronization completed: " +
                    "usersDiscovered={}, usersProcessed={}, " +
                    "emailsInspected={}, emailsProcessed={}, " +
                    "emailsSkipped={}, emailsFailed={}, tasksExtracted={}",
                    result.usersDiscovered(),
                    result.usersProcessed(),
                    result.emailsInspected(),
                    result.emailsProcessed(),
                    result.emailsSkipped(),
                    result.emailsFailed(),
                    result.tasksExtracted()
            );

        } catch (Exception exception) {

            log.error(
                    "Automatic tenant email synchronization failed",
                    exception
            );
        }
    }

    /**
     * Periodically checks all tenant mailboxes' Sent Items for outbound
     * replies, so SlaService can match them against WAITING SLA rows.
     * Runs on the same fixed-delay cadence as Inbox sync — separate
     * Graph delta cursor, same schedule family.
     */
    @Scheduled(
            fixedDelayString = "${email.sync.fixed-delay:60000}"
    )
    public void synchronizeSentItems() {

        log.info("Starting automatic Sent Items synchronization (SLA reply detection)");

        try {

            TenantSentItemsSyncService.SentItemsSyncResult result =
                    tenantSentItemsSyncService.syncTenant(
                            tenantGraphEmailService.getTenantUsers()
                    );

            log.info(
                    "Sent Items synchronization completed: usersProcessed={}, usersFailed={}, repliesMatched={}",
                    result.usersProcessed(),
                    result.usersFailed(),
                    result.repliesMatched()
            );

        } catch (Exception exception) {

            log.error(
                    "Automatic Sent Items synchronization failed",
                    exception
            );
        }
    }
}