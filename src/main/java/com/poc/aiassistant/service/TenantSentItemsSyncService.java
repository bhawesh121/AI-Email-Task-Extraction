package com.poc.aiassistant.service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.entity.EmailSentSyncState;
import com.poc.aiassistant.repository.EmailSentSyncStateRepository;

/**
 * Synchronizes each tenant mailbox's Sent Items folder so outbound
 * replies can be matched against WAITING SLA rows (see SlaService).
 *
 * Deliberately mirrors {@link TenantEmailSyncService}'s Inbox delta
 * loop (opaque nextLink/deltaLink walking, 410 => reset-once, capped
 * pages per run) rather than introducing a new sync framework — only
 * the destination folder and the downstream action differ: instead
 * of registering the message onto the task-extraction queue, each
 * newly-seen sent message is handed to {@link SlaService#recordReply}.
 *
 * On the first synchronization for a mailbox, a configurable historical
 * lookback is used instead of the exact application startup timestamp.
 * This allows replies that were sent shortly before the application
 * started to be reconciled against existing WAITING SLA records.
 */
@Service
public class TenantSentItemsSyncService {

    private static final Logger log =
            LoggerFactory.getLogger(TenantSentItemsSyncService.class);

    private final TenantGraphEmailService tenantGraphEmailService;
    private final EmailSentSyncStateRepository emailSentSyncStateRepository;
    private final SlaService slaService;
    private final ApplicationRuntime applicationRuntime;
    private final int maxDeltaPagesPerUserPerRun;
    private final int initialLookbackDays;

    public TenantSentItemsSyncService(
            TenantGraphEmailService tenantGraphEmailService,
            EmailSentSyncStateRepository emailSentSyncStateRepository,
            SlaService slaService,
            ApplicationRuntime applicationRuntime,
            @Value("${email.sync.max-delta-pages-per-user-per-run:5}")
            int maxDeltaPagesPerUserPerRun,
            @Value("${email.sync.sent-items.initial-lookback-days:7}")
            int initialLookbackDays
    ) {
        this.tenantGraphEmailService = tenantGraphEmailService;
        this.emailSentSyncStateRepository = emailSentSyncStateRepository;
        this.slaService = slaService;
        this.applicationRuntime = applicationRuntime;
        this.maxDeltaPagesPerUserPerRun = Math.max(1, maxDeltaPagesPerUserPerRun);
        this.initialLookbackDays = Math.max(1, initialLookbackDays);
    }

    /** Synchronizes Sent Items for every currently-known tenant user. */
    public SentItemsSyncResult syncTenant(
            List<TenantGraphEmailService.TenantUser> users
    ) {
        /*
         * Only the initial synchronization uses this boundary.
         * Once a mailbox has an established Graph delta link, subsequent
         * runs continue from that opaque delta link and do not rescan
         * the lookback window.
         */
        Instant startupBoundary = applicationRuntime.startedAt()
                .minus(Duration.ofDays(initialLookbackDays));

        int usersProcessed = 0;
        int usersFailed = 0;
        int matched = 0;

        List<String> errors = new ArrayList<>();

        for (TenantGraphEmailService.TenantUser user : users) {
            try {
                int userMatched = syncMailbox(
                        user.id(),
                        startupBoundary
                );

                matched += userMatched;
                usersProcessed++;

            } catch (Exception exception) {
                usersFailed++;

                String error =
                        "Sent Items sync failed for user " + user.id() + ": "
                                + safeMessage(exception);

                errors.add(error);

                log.error(
                        "Sent Items sync failed: userId={}",
                        user.id(),
                        exception
                );
            }
        }

        log.info(
                "Sent Items sync complete: usersProcessed={}, usersFailed={}, repliesMatched={}",
                usersProcessed,
                usersFailed,
                matched
        );

        return new SentItemsSyncResult(
                usersProcessed,
                usersFailed,
                matched,
                errors
        );
    }

    private int syncMailbox(
            String userId,
            Instant startupBoundary
    ) {
        return syncMailbox(
                userId,
                startupBoundary,
                false
        );
    }

    private int syncMailbox(
            String userId,
            Instant startupBoundary,
            boolean alreadyResetOnce
    ) {
        EmailSentSyncState state =
                emailSentSyncStateRepository.findById(userId).orElse(null);

        String deltaLink =
                state == null ? null : state.getDeltaLink();

        String nextLink =
                state == null ? null : state.getDeltaNextLink();

        boolean initialSync =
                deltaLink == null || deltaLink.isBlank();

        String currentLink =
                nextLink != null && !nextLink.isBlank()
                        ? nextLink
                        : deltaLink;

        int matched = 0;
        int pagesProcessed = 0;

        try {
            while (true) {
                TenantGraphEmailService.DeltaPage page =
                        tenantGraphEmailService.getSentItemsDeltaPage(
                                userId,
                                currentLink,
                                initialSync ? startupBoundary : null
                        );

                pagesProcessed++;

                for (TenantGraphEmailService.DeltaMessage message
                        : page.messages()) {

                    if (message.removed()) {
                        continue;
                    }

                    /*
                     * During an initial sync, ignore sent messages that are
                     * older than the historical reconciliation boundary.
                     *
                     * During incremental delta syncs, the boundary is null
                     * and Graph itself defines the changed-message window.
                     */
                    if (initialSync
                            && !isSentAfter(
                                    message.receivedDateTime(),
                                    startupBoundary
                            )) {
                        continue;
                    }

                    try {
                         EmailDto sentMessage =
                                tenantGraphEmailService.getMessage(
                                        userId,
                                        message.messageId()
                                );

                        if (slaService.recordReply(sentMessage)) {
                            matched++;
                        }

                    } catch (
                            TenantGraphEmailService.MessageNoLongerAvailableException gone
                    ) {
                        log.debug(
                                "Sent Items message no longer available: userId={}, messageId={}",
                                userId,
                                message.messageId()
                        );
                    }
                }

                /*
                 * Graph returned another page.
                 * Persist the exact opaque nextLink so a later run can resume
                 * without restarting the mailbox synchronization.
                 */
                if (page.nextLink() != null
                        && !page.nextLink().isBlank()) {

                    EmailSentSyncState progress =
                            progressState(userId);

                    progress.setDeltaNextLink(page.nextLink());

                    emailSentSyncStateRepository.saveAndFlush(progress);

                    currentLink = page.nextLink();

                    if (pagesProcessed >= maxDeltaPagesPerUserPerRun) {
                        return matched;
                    }

                    continue;
                }

                /*
                 * Initial or incremental synchronization completed.
                 * Persist the final deltaLink returned by Microsoft Graph.
                 */
                String finalDeltaLink = page.deltaLink();

                if (finalDeltaLink == null
                        || finalDeltaLink.isBlank()) {

                    throw new IllegalStateException(
                            "Microsoft Graph Sent Items delta completed "
                                    + "without @odata.deltaLink for user "
                                    + userId
                    );
                }

                EmailSentSyncState finalState =
                        progressState(userId);

                finalState.setDeltaLink(finalDeltaLink);
                finalState.setDeltaNextLink(null);

                emailSentSyncStateRepository.saveAndFlush(finalState);

                return matched;
            }

        } catch (
                TenantGraphEmailService.DeltaTokenExpiredException expired
        ) {
            /*
             * If Microsoft invalidates the existing delta token, clear the
             * mailbox cursor once and rebuild it from the configured
             * historical boundary.
             */
            if (alreadyResetOnce) {
                throw expired;
            }

            log.warn(
                    "Resetting expired Sent Items Graph delta token: userId={}",
                    userId
            );

            EmailSentSyncState resetState =
                    emailSentSyncStateRepository.findById(userId)
                            .orElse(null);

            if (resetState != null) {
                resetState.setDeltaLink(null);
                resetState.setDeltaNextLink(null);

                emailSentSyncStateRepository.saveAndFlush(resetState);
            }

            return syncMailbox(
                    userId,
                    startupBoundary,
                    true
            );
        }
    }

    private EmailSentSyncState progressState(String userId) {
        return emailSentSyncStateRepository.findById(userId)
                .orElseGet(() -> {
                    EmailSentSyncState created =
                            new EmailSentSyncState();

                    created.setMailboxUserId(userId);

                    return created;
                });
    }

    private boolean isSentAfter(
            String receivedDateTime,
            Instant boundary
    ) {
        if (receivedDateTime == null
                || receivedDateTime.isBlank()) {
            return false;
        }

        try {
            return OffsetDateTime
                    .parse(receivedDateTime)
                    .toInstant()
                    .isAfter(boundary);

        } catch (Exception exception) {
            log.warn(
                    "Ignoring Sent Items delta message with invalid timestamp: {}",
                    receivedDateTime
            );

            return false;
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();

        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    public record SentItemsSyncResult(
            int usersProcessed,
            int usersFailed,
            int repliesMatched,
            List<String> errors
    ) {
    }
}