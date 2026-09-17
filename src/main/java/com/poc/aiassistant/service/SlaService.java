package com.poc.aiassistant.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.poc.aiassistant.dto.BreachedEmailDto;
import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.SlaSummaryDto;
import com.poc.aiassistant.dto.SlaTrendPointDto;
import com.poc.aiassistant.entity.EmailIntelligence;
import com.poc.aiassistant.entity.EmailSla;
import com.poc.aiassistant.entity.EmailSlaStatus;
import com.poc.aiassistant.repository.EmailIntelligenceRepository;
import com.poc.aiassistant.repository.EmailSlaRepository;

/**
 * Email-level Responsiveness/SLA tracking.
 *
 * Deliberately independent from Task lifecycle:
 * Task completion never marks an SLA completed,
 * and SLA completion never affects Task status.
 */
@Service
public class SlaService {

    private static final Logger log =
            LoggerFactory.getLogger(SlaService.class);

    private final EmailSlaRepository emailSlaRepository;
    private final EmailIntelligenceRepository emailIntelligenceRepository;
    private final SlaEligibilityService slaEligibilityService;
    private final BusinessHoursSlaCalculator businessHoursSlaCalculator;

    public SlaService(
            EmailSlaRepository emailSlaRepository,
            EmailIntelligenceRepository emailIntelligenceRepository,
            SlaEligibilityService slaEligibilityService,
            BusinessHoursSlaCalculator businessHoursSlaCalculator
    ) {
        this.emailSlaRepository = emailSlaRepository;
        this.emailIntelligenceRepository = emailIntelligenceRepository;
        this.slaEligibilityService = slaEligibilityService;
        this.businessHoursSlaCalculator = businessHoursSlaCalculator;
    }

    /**
     * Entry point from the email-processing pipeline.
     *
     * Persists EmailIntelligence idempotently and creates an SLA
     * only when the email is eligible for response SLA tracking.
     */
    @Transactional
    public void evaluateAndCreate(
            EmailDto email,
            boolean responseRequired,
            boolean requiresAction
    ) {
        if (email == null
                || email.mailbox() == null
                || email.id() == null) {
            return;
        }

        persistIntelligence(
                email,
                responseRequired,
                requiresAction
        );

        String senderDomain =
                domainOf(email);

        if (!responseRequired) {
            log.debug(
                    "SLA not applicable (responseRequired=false): "
                            + "mailbox={}, messageId={}",
                    email.mailbox(),
                    email.id()
            );
            return;
        }

        if (!slaEligibilityService.isEligibleForResponseSla(
                email,
                responseRequired
        )) {
            log.debug(
                    "SLA not applicable (sender is internal tenant domain "
                            + "or sender domain is unavailable): "
                            + "mailbox={}, messageId={}, senderDomain={}",
                    email.mailbox(),
                    email.id(),
                    senderDomain
            );
            return;
        }

        if (emailSlaRepository
                .findByMailboxUserIdAndMessageId(
                        email.mailbox(),
                        email.id()
                )
                .isPresent()) {

            log.debug(
                    "SLA already exists for this email "
                            + "(idempotent skip): mailbox={}, messageId={}",
                    email.mailbox(),
                    email.id()
            );

            return;
        }

        OffsetDateTime receivedAt =
                parseTimestamp(
                        email.receivedDateTime()
                );

        if (receivedAt == null) {
            log.warn(
                    "Cannot create SLA: unparseable receivedDateTime. "
                            + "mailbox={}, messageId={}, value={}",
                    email.mailbox(),
                    email.id(),
                    email.receivedDateTime()
            );
            return;
        }

        OffsetDateTime deadline =
                businessHoursSlaCalculator.calculateDeadline(
                        receivedAt
                );

        EmailSla sla =
                new EmailSla(
                        email.mailbox(),
                        email.id(),
                        email.conversationId(),
                        email.senderEmail(),
                        senderDomain,
                        email.subject(),
                        receivedAt,
                        receivedAt,
                        deadline
                );

        emailSlaRepository.save(sla);

        log.info(
                "SLA created: mailbox={}, messageId={}, "
                        + "customerDomain={}, deadline={}",
                email.mailbox(),
                email.id(),
                senderDomain,
                deadline
        );
    }

    /**
     * Records the first response to an SLA.
     *
     * Matching hierarchy:
     *
     * 1. Primary:
     *      conversationId
     *
     * 2. Fallback:
     *      customer email across all mailboxes
     *      + unresolved SLA
     *      + sentAt > receivedAt
     *      + customer is a recipient
     *      + exactly one unique candidate
     *
     * The fallback deliberately does NOT require:
     * - same mailbox
     * - same subject
     *
     * This is required by the actual Outlook data observed in the
     * Sent Items reconciliation.
     */
    @Transactional
    public boolean recordReply(
            EmailDto sentMessage
    ) {

        if (sentMessage == null) {
            log.info(
                    "SLA reply ignored: sentMessage is null"
            );

            return false;
        }

        OffsetDateTime sentAt =
                parseTimestamp(
                        sentMessage.receivedDateTime()
                );

        if (sentAt == null) {
            log.info(
                    "SLA reply ignored: unable to parse sent timestamp. "
                            + "mailbox={}, conversationId={}, "
                            + "replyMessageId={}, timestamp={}",
                    sentMessage.mailbox(),
                    sentMessage.conversationId(),
                    sentMessage.id(),
                    sentMessage.receivedDateTime()
            );

            return false;
        }

        List<String> replyRecipients =
                normalizedEmails(
                        sentMessage.recipientEmails()
                );

        /*
         * ---------------------------------------------------------
         * PRIMARY MATCH
         * ---------------------------------------------------------
         *
         * Prefer conversationId whenever Graph provides it.
         */
        List<EmailSla> candidates =
                List.of();

        boolean fallbackUsed =
                false;

        if (sentMessage.conversationId() != null
                && !sentMessage.conversationId().isBlank()) {

            candidates =
                    emailSlaRepository
                            .findByConversationIdAndFirstResponseAtIsNull(
                                    sentMessage.conversationId()
                            );
        }

        /*
         * ---------------------------------------------------------
         * FALLBACK MATCH
         * ---------------------------------------------------------
         *
         * If conversationId did not identify an SLA, search for
         * unresolved SLAs for the customer across ALL mailboxes.
         */
        if (candidates.isEmpty()) {

            fallbackUsed =
                    true;

            log.info(
                    "Primary SLA conversation lookup returned no candidates: "
                            + "mailbox={}, conversationId={}, "
                            + "replyMessageId={}, subject={}, recipients={}",
                    sentMessage.mailbox(),
                    sentMessage.conversationId(),
                    sentMessage.id(),
                    sentMessage.subject(),
                    replyRecipients
            );

            candidates =
                    findFallbackCandidates(
                            sentMessage,
                            sentAt,
                            replyRecipients
                    );
        }

        if (candidates.isEmpty()) {

            log.info(
                    "No waiting SLA candidate for sent reply: "
                            + "mailbox={}, conversationId={}, replyMessageId={}",
                    sentMessage.mailbox(),
                    sentMessage.conversationId(),
                    sentMessage.id()
            );

            return false;
        }

        boolean matchedAny =
                false;

        for (EmailSla candidate : candidates) {

            /*
             * Primary conversation matching retains the original
             * same-mailbox validation.
             *
             * Fallback matching intentionally permits a different
             * responding mailbox.
             */
            boolean sameMailbox =
                    candidate.getMailboxUserId() != null
                            && candidate
                            .getMailboxUserId()
                            .equals(
                                    sentMessage.mailbox()
                            );

            boolean mailboxMatches =
                    fallbackUsed
                            || sameMailbox;

            /*
             * Response must occur after the original email.
             */
            boolean sentAfterReceived =
                    candidate.getReceivedAt() != null
                            && sentAt.isAfter(
                                    candidate.getReceivedAt()
                            );

            /*
             * The original customer must be among the recipients
             * of the outbound response.
             */
            boolean recipientMatches =
                    candidate.getCustomerEmail() != null
                            && replyRecipients.contains(
                                    candidate
                                            .getCustomerEmail()
                                            .trim()
                                            .toLowerCase(
                                                    Locale.ROOT
                                            )
                            );

            if (!mailboxMatches
                    || !sentAfterReceived
                    || !recipientMatches) {

                log.info(
                        "SLA reply rejected: "
                                + "originalMessageId={}, "
                                + "replyMessageId={}, "
                                + "fallbackUsed={}, "
                                + "sameMailbox={}, "
                                + "mailboxMatches={}, "
                                + "sentAfterReceived={}, "
                                + "recipientMatches={}, "
                                + "slaCustomerEmail={}, "
                                + "replyRecipients={}, "
                                + "slaMailbox={}, "
                                + "replyMailbox={}, "
                                + "slaReceivedAt={}, "
                                + "replySentAt={}",
                        candidate.getMessageId(),
                        sentMessage.id(),
                        fallbackUsed,
                        sameMailbox,
                        mailboxMatches,
                        sentAfterReceived,
                        recipientMatches,
                        candidate.getCustomerEmail(),
                        replyRecipients,
                        candidate.getMailboxUserId(),
                        sentMessage.mailbox(),
                        candidate.getReceivedAt(),
                        sentAt
                );

                continue;
            }

            candidate.recordFirstResponse(
                    sentAt,
                    sentMessage.id(),
                    sentMessage.mailbox()
            );

            emailSlaRepository.save(
                    candidate
            );

            log.info(
                    "SLA reply matched: "
                            + "mailboxUserId={}, "
                            + "originalMessageId={}, "
                            + "replyMessageId={}, "
                            + "respondedAt={}, "
                            + "resultingStatus={}, "
                            + "fallbackUsed={}",
                    candidate.getMailboxUserId(),
                    candidate.getMessageId(),
                    sentMessage.id(),
                    sentAt,
                    candidate.getStatus(),
                    fallbackUsed
            );

            matchedAny =
                    true;
        }

        return matchedAny;
    }

    /**
     * Fallback SLA correlation.
     *
     * Important:
     * - customer lookup is across all mailboxes
     * - subject is NOT used
     * - mailbox is NOT used as a restriction
     * - sentAt must be after original receivedAt
     * - exactly one candidate must remain
     *
     * If there are multiple unresolved SLA rows for the same customer,
     * we deliberately do not guess.
     */
    private List<EmailSla> findFallbackCandidates(
            EmailDto sentMessage,
            OffsetDateTime sentAt,
            List<String> replyRecipients
    ) {

        if (replyRecipients.isEmpty()) {
            log.info(
                    "SLA fallback skipped: Sent Items message has "
                            + "no recipients. replyMessageId={}",
                    sentMessage.id()
            );

            return List.of();
        }

        /*
         * A message can contain the same customer in both To and Cc.
         * Use messageId to deduplicate candidates.
         */
        Map<String, EmailSla> uniqueCandidates =
                new LinkedHashMap<>();

        for (String recipient : replyRecipients) {

            List<EmailSla> customerSlas =
                    emailSlaRepository
                            .findByCustomerEmailIgnoreCaseAndFirstResponseAtIsNull(
                                    recipient
                            );

            log.info(
                    "SLA fallback customer lookup: "
                            + "customerEmail={}, candidateCount={}",
                    recipient,
                    customerSlas.size()
            );

            for (EmailSla candidate : customerSlas) {

                /*
                 * A response cannot satisfy an SLA whose original
                 * email arrived after the response.
                 */
                if (candidate.getReceivedAt() == null
                        || !sentAt.isAfter(
                        candidate.getReceivedAt()
                )) {

                    log.info(
                            "SLA fallback candidate rejected by time: "
                                    + "originalMessageId={}, "
                                    + "receivedAt={}, sentAt={}",
                            candidate.getMessageId(),
                            candidate.getReceivedAt(),
                            sentAt
                    );

                    continue;
                }

                String key =
                        candidate.getMessageId() != null
                                ? candidate.getMessageId()
                                : String.valueOf(
                                candidate.getId()
                        );

                uniqueCandidates.put(
                        key,
                        candidate
                );
            }
        }

        /*
         * Exactly one candidate is required.
         */
        if (uniqueCandidates.size() == 1) {

            EmailSla candidate =
                    uniqueCandidates.values()
                            .iterator()
                            .next();

            log.info(
                    "Using fallback SLA correlation: "
                            + "replyMessageId={}, "
                            + "originalMessageId={}, "
                            + "slaMailbox={}, "
                            + "replyMailbox={}, "
                            + "customerEmail={}",
                    sentMessage.id(),
                    candidate.getMessageId(),
                    candidate.getMailboxUserId(),
                    sentMessage.mailbox(),
                    candidate.getCustomerEmail()
            );

            return List.of(
                    candidate
            );
        }

        /*
         * Never guess when multiple unresolved SLAs exist.
         */
        if (uniqueCandidates.size() > 1) {

            log.warn(
                    "Ambiguous fallback SLA correlation: "
                            + "replyMessageId={}, "
                            + "customerRecipients={}, "
                            + "candidateCount={}",
                    sentMessage.id(),
                    replyRecipients,
                    uniqueCandidates.size()
            );

            return List.of();
        }

        return List.of();
    }

    /**
     * Periodic sweep:
     * flips WAITING rows whose deadline has passed to BREACHED.
     */
    @Transactional
    public int sweepBreaches(
            OffsetDateTime now
    ) {
        List<EmailSla> overdue =
                emailSlaRepository
                        .findByStatusAndSlaDeadlineAtBefore(
                                EmailSlaStatus.WAITING,
                                now
                        );

        for (EmailSla sla : overdue) {
            sla.markBreached();
        }

        if (!overdue.isEmpty()) {

            emailSlaRepository.saveAll(
                    overdue
            );

            log.info(
                    "SLA breach sweep marked {} email(s) BREACHED",
                    overdue.size()
            );
        }

        return overdue.size();
    }

    @Transactional(readOnly = true)
    public SlaSummaryDto getSummary(
            LocalDate date
    ) {
        OffsetDateTime from =
                date.atStartOfDay()
                        .atOffset(
                                ZoneOffset.UTC
                        );

        OffsetDateTime to =
                from.plusDays(1);

        return summaryFor(
                date,
                from,
                to
        );
    }

    @Transactional(readOnly = true)
    public List<SlaTrendPointDto> getTrend(
            int days
    ) {
        List<SlaTrendPointDto> points =
                new ArrayList<>();

        LocalDate today =
                LocalDate.now(
                        ZoneOffset.UTC
                );

        for (int i = days - 1; i >= 0; i--) {

            LocalDate day =
                    today.minusDays(i);

            OffsetDateTime from =
                    day.atStartOfDay()
                            .atOffset(
                                    ZoneOffset.UTC
                            );

            OffsetDateTime to =
                    from.plusDays(1);

            long total =
                    emailSlaRepository
                            .countByReceivedAtBetween(
                                    from,
                                    to
                            );

            long completed =
                    emailSlaRepository
                            .countByStatusAndReceivedAtBetween(
                                    EmailSlaStatus.COMPLETED,
                                    from,
                                    to
                            );

            long waiting =
                    emailSlaRepository
                            .countByStatusAndReceivedAtBetween(
                                    EmailSlaStatus.WAITING,
                                    from,
                                    to
                            );

            long breached =
                    emailSlaRepository
                            .countByStatusAndReceivedAtBetween(
                                    EmailSlaStatus.BREACHED,
                                    from,
                                    to
                            );

            points.add(
                    new SlaTrendPointDto(
                            day,
                            total,
                            completed,
                            waiting,
                            breached
                    )
            );
        }

        return points;
    }

    @Transactional(readOnly = true)
    public List<BreachedEmailDto> getBreachedEmails(
            LocalDate from,
            LocalDate to,
            String mailboxUserId
    ) {
        OffsetDateTime fromTs =
                from == null
                        ? null
                        : from.atStartOfDay()
                        .atOffset(
                                ZoneOffset.UTC
                        );

        OffsetDateTime toTs =
                to == null
                        ? null
                        : to.plusDays(1)
                        .atStartOfDay()
                        .atOffset(
                                ZoneOffset.UTC
                        );

        Specification<EmailSla> specification =
                (root, query, criteriaBuilder) -> {

                    var predicates =
                            criteriaBuilder.conjunction();

                    predicates.getExpressions().add(
                            criteriaBuilder.equal(
                                    root.<EmailSlaStatus>get(
                                            "status"
                                    ),
                                    EmailSlaStatus.BREACHED
                            )
                    );

                    if (fromTs != null) {
                        predicates.getExpressions().add(
                                criteriaBuilder
                                        .greaterThanOrEqualTo(
                                                root.<OffsetDateTime>get(
                                                        "receivedAt"
                                                ),
                                                fromTs
                                        )
                        );
                    }

                    if (toTs != null) {
                        predicates.getExpressions().add(
                                criteriaBuilder.lessThan(
                                        root.<OffsetDateTime>get(
                                                "receivedAt"
                                        ),
                                        toTs
                                )
                        );
                    }

                    if (mailboxUserId != null
                            && !mailboxUserId.isBlank()) {

                        predicates.getExpressions().add(
                                criteriaBuilder.equal(
                                        root.<String>get(
                                                "mailboxUserId"
                                        ),
                                        mailboxUserId.trim()
                                )
                        );
                    }

                    return predicates;
                };

        return emailSlaRepository
                .findAll(
                        specification,
                        Sort.by(
                                Sort.Direction.DESC,
                                "receivedAt"
                        )
                )
                .stream()
                .map(
                        this::toBreachedDto
                )
                .toList();
    }

    /**
     * Summary calculation.
     *
     * When there are no SLA records for the requested day,
     * complianceRate is null because a percentage cannot be
     * meaningfully calculated from 0 / 0.
     */
    private SlaSummaryDto summaryFor(
            LocalDate date,
            OffsetDateTime from,
            OffsetDateTime to
    ) {
        long total =
                emailSlaRepository
                        .countByReceivedAtBetween(
                                from,
                                to
                        );

        long completed =
                emailSlaRepository
                        .countByStatusAndReceivedAtBetween(
                                EmailSlaStatus.COMPLETED,
                                from,
                                to
                        );

        long waiting =
                emailSlaRepository
                        .countByStatusAndReceivedAtBetween(
                                EmailSlaStatus.WAITING,
                                from,
                                to
                        );

        long breached =
                emailSlaRepository
                        .countByStatusAndReceivedAtBetween(
                                EmailSlaStatus.BREACHED,
                                from,
                                to
                        );

        Double complianceRate =
                total == 0
                        ? null
                        : round1(
                                (completed * 100.0) / total
                        );

        return new SlaSummaryDto(
                date,
                total,
                completed,
                waiting,
                breached,
                complianceRate
        );
    }

    private BreachedEmailDto toBreachedDto(
            EmailSla sla
    ) {
        Long delayMinutes =
                null;

        if (sla.getFirstResponseAt() != null) {
            delayMinutes =
                    java.time.Duration
                            .between(
                                    sla.getSlaDeadlineAt(),
                                    sla.getFirstResponseAt()
                            )
                            .toMinutes();
        }

        return new BreachedEmailDto(
                sla.getId(),
                sla.getCustomerEmail(),
                sla.getCustomerDomain(),
                sla.getSubject(),
                sla.getReceivedAt(),
                sla.getSlaDeadlineAt(),
                sla.getFirstResponseAt(),
                delayMinutes,
                sla.getStatus(),
                sla.getRespondedBy()
        );
    }

    private void persistIntelligence(
            EmailDto email,
            boolean responseRequired,
            boolean requiresAction
    ) {
        if (emailIntelligenceRepository
                .findByMailboxAndMessageId(
                        email.mailbox(),
                        email.id()
                )
                .isPresent()) {
            return;
        }

        OffsetDateTime receivedAt =
                parseTimestamp(
                        email.receivedDateTime()
                );

        if (receivedAt == null) {
            receivedAt =
                    OffsetDateTime.now(
                            ZoneOffset.UTC
                    );
        }

        EmailIntelligence intelligence =
                new EmailIntelligence(
                        email.mailbox(),
                        email.id(),
                        email.conversationId(),
                        receivedAt,
                        email.senderName(),
                        email.senderEmail(),
                        domainOf(email),
                        email.subject(),
                        responseRequired,
                        requiresAction
                );

        emailIntelligenceRepository.save(
                intelligence
        );
    }

    private String domainOf(
            EmailDto email
    ) {
        if (email.senderDomain() != null
                && !email.senderDomain().isBlank()) {

            return email.senderDomain()
                    .toLowerCase(
                            Locale.ROOT
                    );
        }

        String senderEmail =
                email.senderEmail();

        if (senderEmail == null) {
            return null;
        }

        int at =
                senderEmail.lastIndexOf('@');

        if (at < 0
                || at == senderEmail.length() - 1) {
            return null;
        }

        return senderEmail
                .substring(at + 1)
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private List<String> normalizedEmails(
            List<String> emails
    ) {
        if (emails == null) {
            return List.of();
        }

        return emails.stream()
                .filter(
                        e ->
                                e != null
                                        && !e.isBlank()
                )
                .map(
                        e ->
                                e.trim()
                                        .toLowerCase(
                                                Locale.ROOT
                                        )
                )
                .toList();
    }

    private OffsetDateTime parseTimestamp(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return null;
        }

        try {
            return OffsetDateTime.parse(
                    value
            );
        } catch (DateTimeParseException e) {
            log.warn(
                    "Unable to parse SLA-relevant timestamp: {}",
                    value
            );

            return null;
        }
    }

    private double round1(
            double value
    ) {
        return Math.round(
                value * 10.0
        ) / 10.0;
    }
}