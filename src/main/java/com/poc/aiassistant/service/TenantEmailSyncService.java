package com.poc.aiassistant.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.poc.aiassistant.entity.EmailSyncState;
import com.poc.aiassistant.entity.Employee;
import com.poc.aiassistant.repository.EmailSyncStateRepository;
import com.poc.aiassistant.repository.EmployeeRepository;
import com.poc.aiassistant.util.EmployeeNameNormalizer;

@Service
public class TenantEmailSyncService {

    private static final Logger log = LoggerFactory.getLogger(TenantEmailSyncService.class);

    private final TenantGraphEmailService tenantGraphEmailService;
    private final EmailProcessingService emailProcessingService;
    private final EmployeeRepository employeeRepository;
    private final SlaEligibilityService slaEligibilityService;

    private final ApplicationRuntime applicationRuntime;
    private final EmailSyncStateRepository emailSyncStateRepository;
    private final int maxDeltaPagesPerUserPerRun;

    public TenantEmailSyncService(
            TenantGraphEmailService tenantGraphEmailService,
            EmailProcessingService emailProcessingService,
            EmployeeRepository employeeRepository,
            SlaEligibilityService slaEligibilityService,
            ApplicationRuntime applicationRuntime,
            EmailSyncStateRepository emailSyncStateRepository,
            @Value("${email.sync.max-delta-pages-per-user-per-run:5}") int maxDeltaPagesPerUserPerRun
    ) {

        this.tenantGraphEmailService =
                tenantGraphEmailService;

        this.emailProcessingService =
                emailProcessingService;

        this.employeeRepository =
                employeeRepository;

        this.slaEligibilityService =
                slaEligibilityService;

        this.applicationRuntime =
                applicationRuntime;

        this.emailSyncStateRepository =
                emailSyncStateRepository;
        this.maxDeltaPagesPerUserPerRun = Math.max(1, maxDeltaPagesPerUserPerRun);
    }

    /**
     * Synchronize the inboxes of all users currently
     * returned by Microsoft Graph.
     *
     * Before processing emails, tenant users are also
     * synchronized into the local employees table.
     *
     * This allows assignee resolution to work even when
     * Microsoft Graph provides a recipient name but does
     * not provide the recipient email address.
     */
    public TenantSyncResult syncTenant() {

        Instant startupBoundary = applicationRuntime.startedAt();
        List<TenantGraphEmailService.TenantUser> users = tenantGraphEmailService.getTenantUsers();

        int usersProcessed = 0;
        int usersFailed = 0;
        int employeesCreated = 0;
        int employeesUpdated = 0;
        int discovered = 0;
        int removed = 0;
        List<String> errors = new ArrayList<>();

        log.info(
                "Starting tenant email discovery: users={}, initialBoundary={}",
                users.size(),
                startupBoundary
        );

        for (TenantGraphEmailService.TenantUser user : users) {
            try {
                EmployeeSyncResult employeeResult = synchronizeEmployee(user);
                if (employeeResult == EmployeeSyncResult.CREATED) {
                    employeesCreated++;
                } else if (employeeResult == EmployeeSyncResult.UPDATED) {
                    employeesUpdated++;
                }

                TenantDiscoveryResult result = discoverMailbox(user, startupBoundary);
                discovered += result.discovered();
                removed += result.removed();
                usersProcessed++;

                log.info(
                        "Mailbox discovery completed: userId={}, discovered={}, removed={}, deltaAdvanced={}",
                        user.id(), result.discovered(), result.removed(), result.deltaAdvanced()
                );
            } catch (Exception exception) {
                usersFailed++;
                String error = "Mailbox discovery failed for user " + user.id()
                        + " (" + getMailboxAddress(user) + "): " + safeExceptionMessage(exception);
                errors.add(error);
                log.error("Mailbox discovery failed: userId={}, mailbox={}", user.id(), getMailboxAddress(user), exception);
            }
        }

        log.info(
                "Tenant email discovery complete: usersDiscovered={}, usersProcessed={}, usersFailed={}, discoveredEmails={}, removedEmails={}, employeesCreated={}, employeesUpdated={}",
                users.size(), usersProcessed, usersFailed, discovered, removed, employeesCreated, employeesUpdated
        );

        // The existing sync has just refreshed the local tenant-user snapshot.
        // Invalidate the SLA eligibility cache so queued emails use the latest
        // tenant domains without adding another Graph call to the email hot path.
        slaEligibilityService.refresh();

        return new TenantSyncResult(
                users.size(),
                usersProcessed,
                usersFailed,
                discovered + removed,
                0,
                removed,
                0,
                0,
                errors
        );
    }

    private TenantDiscoveryResult discoverMailbox(
            TenantGraphEmailService.TenantUser user,
            Instant startupBoundary
    ) {
        return discoverMailbox(user, startupBoundary, false);
    }

    private TenantDiscoveryResult discoverMailbox(
            TenantGraphEmailService.TenantUser user,
            Instant startupBoundary,
            boolean alreadyResetOnce
    ) {
        String userId = user.id();
        String mailboxAddress = getMailboxAddress(user);
        EmailSyncState state = emailSyncStateRepository.findById(userId).orElse(null);
        String deltaLink = state == null ? null : state.getDeltaLink();
        String nextLink = state == null ? null : state.getDeltaNextLink();
        boolean initialSync = deltaLink == null || deltaLink.isBlank();
        String currentLink = nextLink != null && !nextLink.isBlank() ? nextLink : deltaLink;

        int discovered = 0;
        int removed = 0;
        int pagesProcessed = 0;

        try {
            while (true) {
                TenantGraphEmailService.DeltaPage page =
                        tenantGraphEmailService.getInboxDeltaPage(
                                userId,
                                currentLink,
                                initialSync ? startupBoundary : null
                        );
                pagesProcessed++;

                for (TenantGraphEmailService.DeltaMessage message : page.messages()) {
                    if (message.removed()) {
                        if (emailProcessingService.markRemoved(userId, message.messageId())) {
                            removed++;
                        }
                        continue;
                    }

                    // Delta can emit read/unread updates for old messages.
                    // Never import a message unless its receivedDateTime is inside
                    // the immutable application eligibility window.
                    if (!isReceivedAfter(message.receivedDateTime(), startupBoundary)) {
                        continue;
                    }

                    if (emailProcessingService.registerPending(
                            userId,
                            mailboxAddress,
                            message.messageId()
                    )) {
                        discovered++;
                    }
                }

                if (page.nextLink() != null && !page.nextLink().isBlank()) {
                    EmailSyncState progressState = emailSyncStateRepository.findById(userId).orElseGet(() -> {
                        EmailSyncState created = new EmailSyncState();
                        created.setMailboxUserId(userId);
                        return created;
                    });
                    // Persisting a nextLink is safe because every record from the page
                    // has already been durably registered. The final deltaLink is still
                    // withheld until the complete walk reaches its terminal page.
                    progressState.setDeltaNextLink(page.nextLink());
                    emailSyncStateRepository.saveAndFlush(progressState);

                    currentLink = page.nextLink();
                    if (pagesProcessed >= maxDeltaPagesPerUserPerRun) {
                        return new TenantDiscoveryResult(discovered, removed, false);
                    }
                    continue;
                }

                String finalDeltaLink = page.deltaLink();
                if (finalDeltaLink == null || finalDeltaLink.isBlank()) {
                    throw new IllegalStateException(
                            "Microsoft Graph delta response completed without @odata.deltaLink for user " + userId
                    );
                }

                EmailSyncState finalState = emailSyncStateRepository.findById(userId).orElseGet(() -> {
                    EmailSyncState created = new EmailSyncState();
                    created.setMailboxUserId(userId);
                    return created;
                });
                finalState.setDeltaLink(finalDeltaLink);
                finalState.setDeltaNextLink(null);
                emailSyncStateRepository.saveAndFlush(finalState);

                return new TenantDiscoveryResult(discovered, removed, true);
            }
        } catch (TenantGraphEmailService.DeltaTokenExpiredException expired) {
            if (alreadyResetOnce) {
                throw expired;
            }
            log.warn("Resetting expired Graph delta token: userId={}", userId);
            EmailSyncState resetState = emailSyncStateRepository.findById(userId).orElse(null);
            if (resetState != null) {
                resetState.setDeltaLink(null);
                resetState.setDeltaNextLink(null);
                emailSyncStateRepository.saveAndFlush(resetState);
            }
            return discoverMailbox(user, startupBoundary, true);
        }
    }

    private boolean isReceivedAfter(String receivedDateTime, Instant boundary) {
        if (receivedDateTime == null || receivedDateTime.isBlank()) {
            return false;
        }
        try {
            return OffsetDateTime.parse(receivedDateTime).toInstant().isAfter(boundary);
        } catch (Exception exception) {
            log.warn("Ignoring delta message with invalid receivedDateTime: {}", receivedDateTime);
            return false;
        }
    }

    private record TenantDiscoveryResult(int discovered, int removed, boolean deltaAdvanced) {
    }

    /**
     * Synchronize one Microsoft 365 tenant user into
     * the local employees table.
     *
     * Email is the primary identity because the Employee
     * entity has a unique constraint on email.
     *
     * If the email is not available, the user is skipped
     * because we cannot safely create an Employee without
     * the required email field.
     */
    private EmployeeSyncResult synchronizeEmployee(
                TenantGraphEmailService.TenantUser user
        ) {

        if (user == null
                || user.id() == null
                || user.id().isBlank()) {

                return EmployeeSyncResult.SKIPPED;
        }

        String microsoftUserId = user.id().trim();
        String email = getUserEmail(user);

        if (email == null || email.isBlank()) {

                System.out.println(
                        "SKIPPED EMPLOYEE SYNC: no usable email for user "
                                + microsoftUserId
                );

                return EmployeeSyncResult.SKIPPED;
        }

        /*
         * Use a deterministic mailbox-derived label instead of Graph displayName.
         * Tasks store the assignee as denormalized text, so editable Graph
         * display names must not create new labels over time.
         */
        String name = EmployeeNameNormalizer.deriveNameFromEmail(email);

        /*
        * Microsoft Graph user id is the stable identity.
        * Prefer it so a Microsoft 365 email-address/display-name change
        * updates the existing employee instead of creating another row.
        */
        Employee existing =
                employeeRepository
                        .findByMicrosoftUserId(microsoftUserId)
                        .orElse(null);

        /*
        * Existing rows created before microsoft_user_id was introduced
        * are matched once by email and then permanently linked to the
        * Graph user id.
        */
        if (existing == null) {
                existing =
                        employeeRepository
                                .findByEmailIgnoreCase(email)
                                .orElse(null);
        }

        /*
         * Last-resort guard against creating a second Employee row when
         * the same logical mailbox arrives with a different Graph identity
         * or address representation but the same canonical local-part.
         */
        if (existing == null && name != null) {
                Employee nameMatch =
                        employeeRepository
                                .findByNameIgnoreCase(name)
                                .orElse(null);

                if (nameMatch != null) {
                        log.warn(
                                "Skipping duplicate employee creation for canonical name={} "
                                        + "existingEmail={}, existingGraphId={}, incomingEmail={}, incomingGraphId={}",
                                name,
                                nameMatch.getEmail(),
                                nameMatch.getMicrosoftUserId(),
                                email,
                                microsoftUserId
                        );
                        return EmployeeSyncResult.SKIPPED;
                }
        }

        if (existing != null) {

                existing.update(name, email);
                existing.setMicrosoftUserId(microsoftUserId);
                employeeRepository.save(existing);

                System.out.println("EMPLOYEE SYNCHRONIZED");
                System.out.println("Name  : " + name);
                System.out.println("Email : " + email);
                System.out.println("Graph : " + microsoftUserId);

                return EmployeeSyncResult.UPDATED;
        }

        Employee employee = new Employee(name, email);
        employee.setMicrosoftUserId(microsoftUserId);
        employeeRepository.save(employee);

        System.out.println("EMPLOYEE CREATED");
        System.out.println("Name  : " + name);
        System.out.println("Email : " + email);
        System.out.println("Graph : " + microsoftUserId);

        return EmployeeSyncResult.CREATED;
        }

    /**
     * Prefer Microsoft Graph 'mail'.
     *
     * If mail is unavailable, use userPrincipalName.
     */
    private String getUserEmail(
            TenantGraphEmailService.TenantUser user
    ) {

        if (user.mail() != null
                && !user.mail().isBlank()) {

            return user.mail().trim();
        }

        if (user.userPrincipalName() != null
                && !user.userPrincipalName().isBlank()) {

            return user.userPrincipalName().trim();
        }

        return null;
    }

    /**
     * Get the mailbox address safely.
     *
     * Graph can return a null 'mail' value for some
     * Microsoft 365 users, so userPrincipalName is used
     * as a fallback.
     */
    private String getMailboxAddress(
            TenantGraphEmailService.TenantUser user
    ) {

        String email =
                getUserEmail(user);

        if (email != null
                && !email.isBlank()) {

            return email;
        }

        return user.id();
    }

    private String safeTrim(
            String value
    ) {

        if (value == null
                || value.isBlank()) {

            return null;
        }

        return value.trim();
    }

    private String safeExceptionMessage(
            Exception exception
    ) {

        String message =
                exception.getMessage();

        if (message == null
                || message.isBlank()) {

            return exception
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }

    private enum EmployeeSyncResult {

        CREATED,

        UPDATED,

        SKIPPED
    }

    /**
     * Result returned by one complete tenant
     * synchronization run.
     */
    public record TenantSyncResult(
            int usersDiscovered,
            int usersProcessed,
            int usersFailed,
            int emailsInspected,
            int emailsProcessed,
            int emailsSkipped,
            int emailsFailed,
            int tasksExtracted,
            List<String> errors
    ) {
    }
}