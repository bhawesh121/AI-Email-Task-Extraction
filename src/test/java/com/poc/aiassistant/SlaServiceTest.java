package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.poc.aiassistant.config.SlaProperties;
import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.entity.EmailSla;
import com.poc.aiassistant.entity.EmailSlaStatus;
import com.poc.aiassistant.repository.EmailIntelligenceRepository;
import com.poc.aiassistant.repository.EmailSlaRepository;
import com.poc.aiassistant.service.BusinessHoursSlaCalculator;
import com.poc.aiassistant.service.SlaEligibilityService;
import com.poc.aiassistant.service.SlaService;

class SlaServiceTest {

    private EmailSlaRepository emailSlaRepository;
    private EmailIntelligenceRepository emailIntelligenceRepository;
    private SlaEligibilityService slaEligibilityService;
    private BusinessHoursSlaCalculator calculator;
    private SlaService service;

    private void setUp() {
        emailSlaRepository =
                mock(EmailSlaRepository.class);

        emailIntelligenceRepository =
                mock(EmailIntelligenceRepository.class);

        slaEligibilityService =
                mock(SlaEligibilityService.class);

        calculator =
                new BusinessHoursSlaCalculator(
                        new SlaProperties()
                );

        service =
                new SlaService(
                        emailSlaRepository,
                        emailIntelligenceRepository,
                        slaEligibilityService,
                        calculator,
                        mock(com.poc.aiassistant.realtime.RealtimeEventService.class)
                );

        when(
                emailIntelligenceRepository
                        .findByMailboxAndMessageId(
                                any(),
                                any()
                        )
        ).thenReturn(
                Optional.empty()
        );

        // Mockito does not run JPA lifecycle callbacks or assign @GeneratedValue
        // identifiers. The production code needs both values after save(), so
        // make the mock repository behave like persisted JPA entities.
        when(emailSlaRepository.save(any(EmailSla.class)))
                .thenAnswer(invocation -> {
                    EmailSla sla = invocation.getArgument(0);
                    stubJpaPersistenceState(sla);
                    return sla;
                });

        when(emailSlaRepository.saveAll(any()))
                .thenAnswer(invocation -> {
                    List<EmailSla> slas = invocation.getArgument(0);
                    slas.forEach(SlaServiceTest::stubJpaPersistenceState);
                    return slas;
                });
    }

    private static void stubJpaPersistenceState(EmailSla sla) {
        if (sla.getId() == null) {
            ReflectionTestUtils.setField(
                    sla,
                    "id",
                    UUID.randomUUID()
            );
        }

        if (sla.getCreatedAt() == null) {
            ReflectionTestUtils.setField(
                    sla,
                    "createdAt",
                    OffsetDateTime.now()
            );
        }

        if (sla.getUpdatedAt() == null) {
            ReflectionTestUtils.setField(
                    sla,
                    "updatedAt",
                    OffsetDateTime.now()
            );
        }
    }

    private EmailDto email(
            String senderEmail,
            String mailbox,
            String messageId,
            String receivedAt
    ) {
        return new EmailDto(
                messageId,
                "Need help with my order",
                "Customer",
                senderEmail,
                senderEmail.substring(
                        senderEmail.indexOf('@') + 1
                ),
                "EXTERNAL",
                receivedAt,
                "body",
                List.of(),
                List.of(),
                mailbox,
                "conv-1"
        );
    }

    @Test
    void case1_notResponseRequired_notActionable_createsNoSla() {

        setUp();

        EmailDto e =
                email(
                        "customer@acme.com",
                        "u1",
                        "m1",
                        "2026-08-26T10:00:00Z"
                );

        service.evaluateAndCreate(
                e,
                false,
                false
        );

        verify(
                emailSlaRepository,
                never()
        ).save(any());
    }

    @Test
    void case2_notResponseRequired_butActionable_createsNoSla() {

        setUp();

        EmailDto e =
                email(
                        "customer@acme.com",
                        "u1",
                        "m1",
                        "2026-08-26T10:00:00Z"
                );

        service.evaluateAndCreate(
                e,
                false,
                true
        );

        verify(
                emailSlaRepository,
                never()
        ).save(any());
    }

    @Test
    void case3_responseRequired_notActionable_createsSlaForExternalDomain() {

        setUp();

        when(
                slaEligibilityService
                        .isEligibleForResponseSla(
                                any(),
                                anyBoolean()
                        )
        ).thenReturn(true);

        EmailDto e =
                email(
                        "customer@acme.com",
                        "u1",
                        "m1",
                        "2026-08-26T10:00:00Z"
                );

        when(
                emailSlaRepository
                        .findByMailboxUserIdAndMessageId(
                                "u1",
                                "m1"
                        )
        ).thenReturn(
                Optional.empty()
        );

        service.evaluateAndCreate(
                e,
                true,
                false
        );

        verify(
                emailSlaRepository,
                times(1)
        ).save(any());
    }

    @Test
    void case4_responseRequired_andActionable_stillCreatesExactlyOneSlaForExternalDomain() {

        setUp();

        when(
                slaEligibilityService
                        .isEligibleForResponseSla(
                                any(),
                                anyBoolean()
                        )
        ).thenReturn(true);

        EmailDto e =
                email(
                        "customer@acme.com",
                        "u1",
                        "m1",
                        "2026-08-26T10:00:00Z"
                );

        when(
                emailSlaRepository
                        .findByMailboxUserIdAndMessageId(
                                "u1",
                                "m1"
                        )
        ).thenReturn(
                Optional.empty()
        );

        service.evaluateAndCreate(
                e,
                true,
                true
        );

        verify(
                emailSlaRepository,
                times(1)
        ).save(any());
    }

    @Test
    void ineligibleSender_responseRequired_createsNoSla() {

        setUp();

        when(
                slaEligibilityService
                        .isEligibleForResponseSla(
                                any(),
                                anyBoolean()
                        )
        ).thenReturn(false);

        EmailDto e =
                email(
                        "employee@contoso.onmicrosoft.com",
                        "u1",
                        "m1",
                        "2026-08-26T10:00:00Z"
                );

        service.evaluateAndCreate(
                e,
                true,
                false
        );

        verify(
                emailSlaRepository,
                never()
        ).save(any());
    }

    @Test
    void duplicateEmail_isNeverCreatedTwice() {

        setUp();

        when(
                slaEligibilityService
                        .isEligibleForResponseSla(
                                any(),
                                anyBoolean()
                        )
        ).thenReturn(true);

        EmailDto e =
                email(
                        "customer@acme.com",
                        "u1",
                        "m1",
                        "2026-08-26T10:00:00Z"
                );

        when(
                emailSlaRepository
                        .findByMailboxUserIdAndMessageId(
                                "u1",
                                "m1"
                        )
        ).thenReturn(
                Optional.of(
                        mock(EmailSla.class)
                )
        );

        service.evaluateAndCreate(
                e,
                true,
                false
        );

        verify(
                emailSlaRepository,
                never()
        ).save(any());
    }

    @Test
    void replyBeforeDeadline_completesTheSla() {

        setUp();

        EmailSla sla =
                new EmailSla(
                        "u1",
                        "m1",
                        "conv-1",
                        "customer@acme.com",
                        "acme.com",
                        "Subject",
                        OffsetDateTime.parse(
                                "2026-08-26T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-08-26T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-08-28T16:00:00Z"
                        )
                );

        when(
                emailSlaRepository
                        .findByConversationIdAndFirstResponseAtIsNull(
                                "conv-1"
                        )
        ).thenReturn(
                List.of(sla)
        );

        EmailDto reply =
                new EmailDto(
                        "reply-1",
                        "Re: Need help",
                        "Agent",
                        "agent@ourcompany.com",
                        "ourcompany.com",
                        "INTERNAL",
                        "2026-08-27T09:00:00Z",
                        "body",
                        List.of(),
                        List.of(
                                "customer@acme.com"
                        ),
                        "u1",
                        "conv-1"
                );

        boolean matched =
                service.recordReply(reply);

        assertTrue(matched);

        ArgumentCaptor<EmailSla> saved =
                ArgumentCaptor.forClass(
                        EmailSla.class
                );

        verify(
                emailSlaRepository
        ).save(saved.capture());

        assertEquals(
                EmailSlaStatus.COMPLETED,
                saved.getValue().getStatus()
        );

        assertEquals(
                OffsetDateTime.parse(
                        "2026-08-27T09:00:00Z"
                ),
                saved.getValue().getFirstResponseAt()
        );
    }

    @Test
    void replyAfterDeadline_marksBreachedButStillRecordsTheLateResponse() {

        setUp();

        EmailSla sla =
                new EmailSla(
                        "u1",
                        "m1",
                        "conv-1",
                        "customer@acme.com",
                        "acme.com",
                        "Subject",
                        OffsetDateTime.parse(
                                "2026-08-26T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-08-26T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-08-28T16:00:00Z"
                        )
                );

        when(
                emailSlaRepository
                        .findByConversationIdAndFirstResponseAtIsNull(
                                "conv-1"
                        )
        ).thenReturn(
                List.of(sla)
        );

        EmailDto lateReply =
                new EmailDto(
                        "reply-1",
                        "Re: Need help",
                        "Agent",
                        "agent@ourcompany.com",
                        "ourcompany.com",
                        "INTERNAL",
                        "2026-08-30T09:00:00Z",
                        "body",
                        List.of(),
                        List.of(
                                "customer@acme.com"
                        ),
                        "u1",
                        "conv-1"
                );

        boolean matched =
                service.recordReply(lateReply);

        assertTrue(matched);

        ArgumentCaptor<EmailSla> saved =
                ArgumentCaptor.forClass(
                        EmailSla.class
                );

        verify(
                emailSlaRepository
        ).save(saved.capture());

        assertEquals(
                EmailSlaStatus.BREACHED,
                saved.getValue().getStatus()
        );

        assertEquals(
                OffsetDateTime.parse(
                        "2026-08-30T09:00:00Z"
                ),
                saved.getValue().getFirstResponseAt()
        );
    }

    @Test
    void replyFromDifferentMailbox_doesNotMatchPrimaryConversationCandidate() {

        setUp();

        EmailSla sla =
                new EmailSla(
                        "u1",
                        "m1",
                        "conv-1",
                        "customer@acme.com",
                        "acme.com",
                        "Subject",
                        OffsetDateTime.parse(
                                "2026-08-26T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-08-26T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-08-28T16:00:00Z"
                        )
                );

        when(
                emailSlaRepository
                        .findByConversationIdAndFirstResponseAtIsNull(
                                "conv-1"
                        )
        ).thenReturn(
                List.of(sla)
        );

        /*
         * Primary conversation match still requires the same mailbox.
         */
        EmailDto reply =
                new EmailDto(
                        "reply-1",
                        "Re: Need help",
                        "Other Agent",
                        "other@ourcompany.com",
                        "ourcompany.com",
                        "INTERNAL",
                        "2026-08-27T09:00:00Z",
                        "body",
                        List.of(),
                        List.of(
                                "customer@acme.com"
                        ),
                        "u2",
                        "conv-1"
                );

        boolean matched =
                service.recordReply(reply);

        assertFalse(matched);

        verify(
                emailSlaRepository,
                never()
        ).save(any());
    }

    @Test
    void replyThatDoesNotIncludeTheCustomerAsRecipient_doesNotMatch() {

        setUp();

        EmailSla sla =
                new EmailSla(
                        "u1",
                        "m1",
                        "conv-1",
                        "customer@acme.com",
                        "acme.com",
                        "Subject",
                        OffsetDateTime.parse(
                                "2026-08-26T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-08-26T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-08-28T16:00:00Z"
                        )
                );

        when(
                emailSlaRepository
                        .findByConversationIdAndFirstResponseAtIsNull(
                                "conv-1"
                        )
        ).thenReturn(
                List.of(sla)
        );

        EmailDto reply =
                new EmailDto(
                        "reply-1",
                        "Re: Something else",
                        "Agent",
                        "agent@ourcompany.com",
                        "ourcompany.com",
                        "INTERNAL",
                        "2026-08-27T09:00:00Z",
                        "body",
                        List.of(),
                        List.of(
                                "someone-else@other.com"
                        ),
                        "u1",
                        "conv-1"
                );

        boolean matched =
                service.recordReply(reply);

        assertFalse(matched);

        verify(
                emailSlaRepository,
                never()
        ).save(any());
    }

    @Test
    void fallback_matchesCustomerAcrossDifferentMailboxAndDifferentSubject() {

        setUp();

        EmailSla sla =
                new EmailSla(
                        "84b9-mailbox",
                        "original-message",
                        "original-conversation",
                        "customer@example.com",
                        "example.com",
                        "New Leather Product Order",
                        OffsetDateTime.parse(
                                "2026-09-11T14:26:50Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-09-11T14:26:50Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-09-15T16:00:00Z"
                        )
                );

        /*
         * Primary conversation lookup fails.
         */
        when(
                emailSlaRepository
                        .findByConversationIdAndFirstResponseAtIsNull(
                                "different-conversation"
                        )
        ).thenReturn(
                List.of()
        );

        /*
         * Fallback finds the customer across mailboxes.
         */
        when(
                emailSlaRepository
                        .findByCustomerEmailIgnoreCaseAndFirstResponseAtIsNull(
                                "customer@example.com"
                        )
        ).thenReturn(
                List.of(sla)
        );

        /*
         * Different mailbox.
         * Different subject.
         *
         * Both must still match in fallback mode.
         */
        EmailDto reply =
                new EmailDto(
                        "reply-message",
                        "RE: Wholesale Order - Production & Delivery Confirmation",
                        "Employee 2",
                        "employee2@company.com",
                        "company.com",
                        "INTERNAL",
                        "2026-09-11T15:00:00Z",
                        "body",
                        List.of(),
                        List.of(
                                "customer@example.com"
                        ),
                        "0bfe-mailbox",
                        "different-conversation"
                );

        boolean matched =
                service.recordReply(reply);

        assertTrue(matched);

        ArgumentCaptor<EmailSla> captor =
                ArgumentCaptor.forClass(
                        EmailSla.class
                );

        verify(
                emailSlaRepository
        ).save(captor.capture());

        assertEquals(
                EmailSlaStatus.COMPLETED,
                captor.getValue().getStatus()
        );

        assertEquals(
                "0bfe-mailbox",
                captor.getValue().getRespondedBy()
        );

        assertEquals(
                OffsetDateTime.parse(
                        "2026-09-11T15:00:00Z"
                ),
                captor.getValue().getFirstResponseAt()
        );
    }

    @Test
    void fallback_matchesCaseInsensitiveCustomerEmail() {

        setUp();

        EmailSla sla =
                new EmailSla(
                        "mailbox-original",
                        "message-1",
                        "conversation-1",
                        "Customer@Example.com",
                        "example.com",
                        "Original subject",
                        OffsetDateTime.parse(
                                "2026-09-11T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-09-11T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-09-15T16:00:00Z"
                        )
                );

        when(
                emailSlaRepository
                        .findByConversationIdAndFirstResponseAtIsNull(
                                "conversation-2"
                        )
        ).thenReturn(
                List.of()
        );

        when(
                emailSlaRepository
                        .findByCustomerEmailIgnoreCaseAndFirstResponseAtIsNull(
                                "customer@example.com"
                        )
        ).thenReturn(
                List.of(sla)
        );

        EmailDto reply =
                new EmailDto(
                        "reply-1",
                        "Completely different subject",
                        "Employee",
                        "employee@company.com",
                        "company.com",
                        "INTERNAL",
                        "2026-09-12T10:00:00Z",
                        "body",
                        List.of(),
                        List.of(
                                "CUSTOMER@EXAMPLE.COM"
                        ),
                        "mailbox-2",
                        "conversation-2"
                );

        boolean matched =
                service.recordReply(reply);

        assertTrue(matched);

        verify(
                emailSlaRepository
        ).save(any());
    }

    @Test
    void fallback_doesNotMatchWhenResponseIsBeforeOriginalEmail() {

        setUp();

        EmailSla sla =
                new EmailSla(
                        "mailbox-original",
                        "message-1",
                        "conversation-1",
                        "customer@example.com",
                        "example.com",
                        "Original subject",
                        OffsetDateTime.parse(
                                "2026-09-12T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-09-12T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-09-15T16:00:00Z"
                        )
                );

        when(
                emailSlaRepository
                        .findByConversationIdAndFirstResponseAtIsNull(
                                "conversation-2"
                        )
        ).thenReturn(
                List.of()
        );

        when(
                emailSlaRepository
                        .findByCustomerEmailIgnoreCaseAndFirstResponseAtIsNull(
                                "customer@example.com"
                        )
        ).thenReturn(
                List.of(sla)
        );

        EmailDto reply =
                new EmailDto(
                        "reply-1",
                        "Different subject",
                        "Employee",
                        "employee@company.com",
                        "company.com",
                        "INTERNAL",
                        "2026-09-11T10:00:00Z",
                        "body",
                        List.of(),
                        List.of(
                                "customer@example.com"
                        ),
                        "mailbox-2",
                        "conversation-2"
                );

        boolean matched =
                service.recordReply(reply);

        assertFalse(matched);

        verify(
                emailSlaRepository,
                never()
        ).save(any());
    }

    @Test
    void fallback_doesNotGuessWhenCustomerHasMultipleUnresolvedSlas() {

        setUp();

        EmailSla first =
                new EmailSla(
                        "mailbox-1",
                        "message-1",
                        "conversation-1",
                        "customer@example.com",
                        "example.com",
                        "First subject",
                        OffsetDateTime.parse(
                                "2026-09-10T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-09-10T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-09-15T16:00:00Z"
                        )
                );

        EmailSla second =
                new EmailSla(
                        "mailbox-2",
                        "message-2",
                        "conversation-2",
                        "customer@example.com",
                        "example.com",
                        "Second subject",
                        OffsetDateTime.parse(
                                "2026-09-11T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-09-11T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-09-15T16:00:00Z"
                        )
                );

        when(
                emailSlaRepository
                        .findByConversationIdAndFirstResponseAtIsNull(
                                "unknown-conversation"
                        )
        ).thenReturn(
                List.of()
        );

        when(
                emailSlaRepository
                        .findByCustomerEmailIgnoreCaseAndFirstResponseAtIsNull(
                                "customer@example.com"
                        )
        ).thenReturn(
                List.of(
                        first,
                        second
                )
        );

        EmailDto reply =
                new EmailDto(
                        "reply-message",
                        "Completely different subject",
                        "Employee",
                        "employee@company.com",
                        "company.com",
                        "INTERNAL",
                        "2026-09-12T10:00:00Z",
                        "body",
                        List.of(),
                        List.of(
                                "customer@example.com"
                        ),
                        "mailbox-3",
                        "unknown-conversation"
                );

        boolean matched =
                service.recordReply(reply);

        assertFalse(matched);

        verify(
                emailSlaRepository,
                never()
        ).save(any());
    }

    @Test
    void fallback_doesNotMatchWhenCustomerIsNotARecipient() {

        setUp();

        EmailSla sla =
                new EmailSla(
                        "mailbox-original",
                        "message-1",
                        "conversation-1",
                        "customer@example.com",
                        "example.com",
                        "Original subject",
                        OffsetDateTime.parse(
                                "2026-09-10T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-09-10T10:00:00Z"
                        ),
                        OffsetDateTime.parse(
                                "2026-09-15T16:00:00Z"
                        )
                );

        when(
                emailSlaRepository
                        .findByConversationIdAndFirstResponseAtIsNull(
                                "unknown-conversation"
                        )
        ).thenReturn(
                List.of()
        );

        when(
                emailSlaRepository
                        .findByCustomerEmailIgnoreCaseAndFirstResponseAtIsNull(
                                "someoneelse@example.com"
                        )
        ).thenReturn(
                List.of()
        );

        EmailDto reply =
                new EmailDto(
                        "reply-1",
                        "Completely different subject",
                        "Employee",
                        "employee@company.com",
                        "company.com",
                        "INTERNAL",
                        "2026-09-12T10:00:00Z",
                        "body",
                        List.of(),
                        List.of(
                                "someoneelse@example.com"
                        ),
                        "mailbox-2",
                        "unknown-conversation"
                );

        boolean matched =
                service.recordReply(reply);

        assertFalse(matched);

        verify(
                emailSlaRepository,
                never()
        ).save(any());
    }

    @Test
    void breachSweep_flipsOverdueWaitingRowsToBreached() {

        setUp();

        OffsetDateTime receivedAt =
                OffsetDateTime.parse(
                        "2026-08-28T10:00:00Z"
                );

        OffsetDateTime slaStartAt =
                OffsetDateTime.parse(
                        "2026-08-28T10:00:00Z"
                );

        OffsetDateTime slaDeadlineAt =
                OffsetDateTime.parse(
                        "2026-08-28T12:00:00Z"
                );

        EmailSla overdue =
                spy(
                        new EmailSla(
                        "mailbox-2",
                        "message-breach-1",
                        "conversation-breach-1",
                        "customer@example.com",
                        "example.com",
                        "Overdue email",
                        receivedAt,
                        slaStartAt,
                        slaDeadlineAt
                )
        );

        ReflectionTestUtils.setField(
                overdue,
                "id",
                UUID.randomUUID()
        );

        ReflectionTestUtils.setField(
                overdue,
                "createdAt",
                receivedAt
        );

        ReflectionTestUtils.setField(
                overdue,
                "updatedAt",
                receivedAt
        );

        when(
                emailSlaRepository
                        .findByStatusAndSlaDeadlineAtBefore(
                                org.mockito.ArgumentMatchers.eq(
                                        EmailSlaStatus.WAITING
                                ),
                                any()
                        )
        ).thenReturn(
                List.of(overdue)
        );

        int count =
                service.sweepBreaches(
                        OffsetDateTime.parse(
                                "2026-08-29T00:00:00Z"
                        )
                );

        assertEquals(
                1,
                count
        );

        verify(
                overdue
        ).markBreached();

        verify(
                emailSlaRepository
        ).saveAll(
                List.of(overdue)
        );
    }
}