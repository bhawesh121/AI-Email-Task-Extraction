package com.poc.aiassistant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import org.mockito.InOrder;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.poc.aiassistant.entity.EmailSyncState;
import com.poc.aiassistant.repository.EmailSyncStateRepository;
import com.poc.aiassistant.repository.EmployeeRepository;
import com.poc.aiassistant.service.ApplicationRuntime;
import com.poc.aiassistant.service.EmailProcessingService;
import com.poc.aiassistant.service.TenantEmailSyncService;
import com.poc.aiassistant.service.TenantGraphEmailService;
import com.poc.aiassistant.service.SlaEligibilityService;

class TenantEmailDeltaSyncTest {

    private TenantGraphEmailService graph;
    private EmailProcessingService processing;
    private EmployeeRepository employees;
    private ApplicationRuntime runtime;
    private EmailSyncStateRepository states;
    private SlaEligibilityService slaEligibilityService;
    private TenantEmailSyncService service;

    @BeforeEach
    void setUp() {
        graph = mock(TenantGraphEmailService.class);
        processing = mock(EmailProcessingService.class);
        employees = mock(EmployeeRepository.class);
        runtime = mock(ApplicationRuntime.class);
        states = mock(EmailSyncStateRepository.class);
        slaEligibilityService = mock(SlaEligibilityService.class);

        service = new TenantEmailSyncService(
                graph,
                processing,
                employees,
                slaEligibilityService,
                runtime,
                states,
                5
        );
    }

    @Test
    void multipleDeltaPagesAreConsumedBeforeCheckpointAdvances() {
        Instant boundary =
                Instant.parse("2026-08-27T10:00:00Z");

        when(runtime.startedAt())
                .thenReturn(boundary);

        TenantGraphEmailService.TenantUser user =
                user();

        when(graph.getTenantUsers())
                .thenReturn(List.of(user));

        when(employees.findByEmailIgnoreCase("user@test"))
                .thenReturn(Optional.empty());

        when(employees.findByNameIgnoreCase("User"))
                .thenReturn(Optional.empty());

        when(states.findById("u1"))
                .thenReturn(Optional.empty());

        /*
         * Initial delta page.
         *
         * The page contains m1 and a nextLink. The final deltaLink
         * must not be persisted yet.
         */
        when(
                graph.getInboxDeltaPage(
                        eq("u1"),
                        isNull(),
                        eq(boundary)
                )
        ).thenReturn(
                new TenantGraphEmailService.DeltaPage(
                        List.of(
                                new TenantGraphEmailService.DeltaMessage(
                                        "m1",
                                        "2026-08-27T10:01:00Z",
                                        false
                                )
                        ),
                        "next-1",
                        null
                )
        );

        /*
         * Second/terminal delta page.
         *
         * The page contains m2 and the final deltaLink.
         */
        when(
                graph.getInboxDeltaPage(
                        eq("u1"),
                        eq("next-1"),
                        eq(boundary)
                )
        ).thenReturn(
                new TenantGraphEmailService.DeltaPage(
                        List.of(
                                new TenantGraphEmailService.DeltaMessage(
                                        "m2",
                                        "2026-08-27T10:02:00Z",
                                        false
                                )
                        ),
                        null,
                        "delta-final"
                )
        );

        when(
                processing.registerPending(
                        eq("u1"),
                        any(),
                        eq("m1")
                )
        ).thenReturn(true);

        when(
                processing.registerPending(
                        eq("u1"),
                        any(),
                        eq("m2")
                )
        ).thenReturn(true);

        ArgumentCaptor<EmailSyncState> stateCaptor =
                ArgumentCaptor.forClass(EmailSyncState.class);

        TenantEmailSyncService.TenantSyncResult result =
                service.syncTenant();

        assertEquals(
                2,
                result.emailsInspected()
        );

        /*
         * Verify the exact durable ordering:
         *
         * page 1:
         *     register m1
         *     persist delta_next_link=next-1
         *
         * page 2:
         *     register m2
         *     persist delta_link=delta-final
         */
        InOrder order =
                inOrder(processing, states);

        order.verify(processing)
                .registerPending(
                        eq("u1"),
                        any(),
                        eq("m1")
                );

        order.verify(states)
                .saveAndFlush(
                        stateCaptor.capture()
                );

        order.verify(processing)
                .registerPending(
                        eq("u1"),
                        any(),
                        eq("m2")
                );

        order.verify(states)
                .saveAndFlush(
                        stateCaptor.capture()
                );

        List<EmailSyncState> savedStates =
                stateCaptor.getAllValues();

        assertEquals(
                2,
                savedStates.size()
        );

        /*
         * Intermediate checkpoint after page 1.
         *
         * The final deltaLink must remain untouched.
         * The next page must be durably resumable.
         */
        EmailSyncState pageOneState =
                savedStates.get(0);

        assertEquals(
                "next-1",
                pageOneState.getDeltaNextLink()
        );

        assertNull(
                pageOneState.getDeltaLink()
        );

        /*
         * Final checkpoint after page 2.
         *
         * The terminal deltaLink becomes authoritative.
         * The intermediate nextLink is cleared.
         */
        EmailSyncState finalState =
                savedStates.get(1);

        assertEquals(
                "delta-final",
                finalState.getDeltaLink()
        );

        assertNull(
                finalState.getDeltaNextLink()
        );

        /*
         * Verify that page 2 was actually requested using the
         * persisted continuation token.
         */
        verify(graph)
                .getInboxDeltaPage(
                        eq("u1"),
                        eq("next-1"),
                        eq(boundary)
                );
    }

    @Test
    void readEmailsAreEligibleBecauseDiscoveryDoesNotInspectIsRead() {
        Instant boundary =
                Instant.parse("2026-08-27T10:00:00Z");

        when(runtime.startedAt())
                .thenReturn(boundary);

        TenantGraphEmailService.TenantUser user =
                user();

        when(graph.getTenantUsers())
                .thenReturn(List.of(user));

        when(employees.findByEmailIgnoreCase("user@test"))
                .thenReturn(Optional.empty());

        when(employees.findByNameIgnoreCase("User"))
                .thenReturn(Optional.empty());

        when(states.findById("u1"))
                .thenReturn(Optional.empty());

        when(
                graph.getInboxDeltaPage(
                        eq("u1"),
                        isNull(),
                        eq(boundary)
                )
        ).thenReturn(
                new TenantGraphEmailService.DeltaPage(
                        List.of(
                                new TenantGraphEmailService.DeltaMessage(
                                        "read-mail",
                                        "2026-08-27T10:05:00Z",
                                        false
                                )
                        ),
                        null,
                        "delta"
                )
        );

        when(
                processing.registerPending(
                        eq("u1"),
                        any(),
                        eq("read-mail")
                )
        ).thenReturn(true);

        service.syncTenant();

        verify(processing)
                .registerPending(
                        eq("u1"),
                        any(),
                        eq("read-mail")
                );
    }

    @Test
    void preBoundaryEmailIsIgnored() {
        Instant boundary =
                Instant.parse("2026-08-27T10:00:00Z");

        when(runtime.startedAt())
                .thenReturn(boundary);

        TenantGraphEmailService.TenantUser user =
                user();

        when(graph.getTenantUsers())
                .thenReturn(List.of(user));

        when(employees.findByEmailIgnoreCase("user@test"))
                .thenReturn(Optional.empty());

        when(employees.findByNameIgnoreCase("User"))
                .thenReturn(Optional.empty());

        when(states.findById("u1"))
                .thenReturn(Optional.empty());

        when(
                graph.getInboxDeltaPage(
                        eq("u1"),
                        isNull(),
                        eq(boundary)
                )
        ).thenReturn(
                new TenantGraphEmailService.DeltaPage(
                        List.of(
                                new TenantGraphEmailService.DeltaMessage(
                                        "old",
                                        "2026-08-27T09:59:59Z",
                                        false
                                )
                        ),
                        null,
                        "delta"
                )
        );

        service.syncTenant();

        verify(
                processing,
                never()
        ).registerPending(
                any(),
                any(),
                any()
        );

        verify(states)
                .saveAndFlush(
                        any(EmailSyncState.class)
                );
    }

    @Test
    void failureBeforeFinalCheckpointLeavesSafeDeltaLinkAndResumesFromPersistedNextLink() {
        Instant boundary =
                Instant.parse("2026-08-27T10:00:00Z");

        when(runtime.startedAt())
                .thenReturn(boundary);

        TenantGraphEmailService.TenantUser user =
                user();

        when(graph.getTenantUsers())
                .thenReturn(
                        List.of(user),
                        List.of(user)
                );

        when(employees.findByEmailIgnoreCase("user@test"))
                .thenReturn(Optional.empty());

        when(employees.findByNameIgnoreCase("User"))
                .thenReturn(Optional.empty());

        EmailSyncState state =
                new EmailSyncState();

        state.setMailboxUserId("u1");
        state.setDeltaLink("safe");

        when(states.findById("u1"))
                .thenReturn(Optional.of(state));

        /*
         * First run starts from the safe deltaLink.
         */
        when(
                graph.getInboxDeltaPage(
                        eq("u1"),
                        eq("safe"),
                        isNull()
                )
        ).thenReturn(
                new TenantGraphEmailService.DeltaPage(
                        List.of(
                                new TenantGraphEmailService.DeltaMessage(
                                        "m1",
                                        "2026-08-27T10:01:00Z",
                                        false
                                )
                        ),
                        "next",
                        null
                )
        );

        /*
         * Page 2 fails on the first run and succeeds on the replay.
         */
        when(
                graph.getInboxDeltaPage(
                        eq("u1"),
                        eq("next"),
                        isNull()
                )
        ).thenThrow(
                new RuntimeException("page failure")
        ).thenReturn(
                new TenantGraphEmailService.DeltaPage(
                        List.of(
                                new TenantGraphEmailService.DeltaMessage(
                                        "m2",
                                        "2026-08-27T10:02:00Z",
                                        false
                                )
                        ),
                        null,
                        "delta-final"
                )
        );

        when(
                processing.registerPending(
                        eq("u1"),
                        any(),
                        eq("m1")
                )
        ).thenReturn(true);

        when(
                processing.registerPending(
                        eq("u1"),
                        any(),
                        eq("m2")
                )
        ).thenReturn(true);

        /*
         * First execution:
         *
         * safe deltaLink stays authoritative.
         * nextLink becomes the durable resume point.
         */
        service.syncTenant();

        assertEquals(
                "safe",
                state.getDeltaLink()
        );

        assertEquals(
                "next",
                state.getDeltaNextLink()
        );

        verify(processing)
                .registerPending(
                        eq("u1"),
                        any(),
                        eq("m1")
                );

        /*
         * Replay:
         *
         * the service resumes from the persisted nextLink,
         * not from the original safe deltaLink page again.
         */
        service.syncTenant();

        assertEquals(
                "delta-final",
                state.getDeltaLink()
        );

        assertNull(
                state.getDeltaNextLink()
        );

        verify(processing)
                .registerPending(
                        eq("u1"),
                        any(),
                        eq("m2")
                );

        /*
         * m1 must only have been registered once.
         *
         * The persisted delta_next_link prevents re-reading page 1
         * merely because page 2 previously failed.
         */
        verify(
                processing,
                times(1)
        ).registerPending(
                eq("u1"),
                any(),
                eq("m1")
        );

        verify(
                states,
                atLeast(2)
        ).saveAndFlush(state);
    }

    @Test
    void readUnreadUpdateForOldMailDoesNotImportIt() {
        Instant boundary =
                Instant.parse("2026-08-27T10:00:00Z");

        when(runtime.startedAt())
                .thenReturn(boundary);

        TenantGraphEmailService.TenantUser user =
                user();

        EmailSyncState state =
                new EmailSyncState();

        state.setMailboxUserId("u1");
        state.setDeltaLink("stored");

        when(graph.getTenantUsers())
                .thenReturn(List.of(user));

        when(employees.findByEmailIgnoreCase("user@test"))
                .thenReturn(Optional.empty());

        when(employees.findByNameIgnoreCase("User"))
                .thenReturn(Optional.empty());

        when(states.findById("u1"))
                .thenReturn(Optional.of(state));

        when(
                graph.getInboxDeltaPage(
                        eq("u1"),
                        eq("stored"),
                        isNull()
                )
        ).thenReturn(
                new TenantGraphEmailService.DeltaPage(
                        List.of(
                                new TenantGraphEmailService.DeltaMessage(
                                        "old",
                                        "2026-08-27T09:00:00Z",
                                        false
                                )
                        ),
                        null,
                        "delta"
                )
        );

        service.syncTenant();

        verify(
                processing,
                never()
        ).registerPending(
                any(),
                any(),
                any()
        );
    }

    @Test
    void completedRemovedEmailIsNotUndone() {
        Instant boundary =
                Instant.parse("2026-08-27T10:00:00Z");

        when(runtime.startedAt())
                .thenReturn(boundary);

        TenantGraphEmailService.TenantUser user =
                user();

        EmailSyncState state =
                new EmailSyncState();

        state.setMailboxUserId("u1");
        state.setDeltaLink("stored");

        when(graph.getTenantUsers())
                .thenReturn(List.of(user));

        when(employees.findByEmailIgnoreCase("user@test"))
                .thenReturn(Optional.empty());

        when(employees.findByNameIgnoreCase("User"))
                .thenReturn(Optional.empty());

        when(states.findById("u1"))
                .thenReturn(Optional.of(state));

        when(
                graph.getInboxDeltaPage(
                        eq("u1"),
                        eq("stored"),
                        isNull()
                )
        ).thenReturn(
                new TenantGraphEmailService.DeltaPage(
                        List.of(
                                new TenantGraphEmailService.DeltaMessage(
                                        "done",
                                        null,
                                        true
                                )
                        ),
                        null,
                        "delta"
                )
        );

        when(
                processing.markRemoved(
                        "u1",
                        "done"
                )
        ).thenReturn(false);

        service.syncTenant();

        verify(processing)
                .markRemoved(
                        "u1",
                        "done"
                );
    }

    @Test
    void expiredDeltaTokenIsResetAndFullSyncRestarts() {
        Instant boundary =
                Instant.parse("2026-08-27T10:00:00Z");

        when(runtime.startedAt())
                .thenReturn(boundary);

        TenantGraphEmailService.TenantUser user =
                user();

        EmailSyncState state =
                new EmailSyncState();

        state.setMailboxUserId("u1");
        state.setDeltaLink("expired");

        when(graph.getTenantUsers())
                .thenReturn(List.of(user));

        when(employees.findByEmailIgnoreCase("user@test"))
                .thenReturn(Optional.empty());

        when(employees.findByNameIgnoreCase("User"))
                .thenReturn(Optional.empty());

        when(states.findById("u1"))
                .thenReturn(Optional.of(state));

        /*
         * Existing delta token is expired.
         */
        when(
                graph.getInboxDeltaPage(
                        eq("u1"),
                        eq("expired"),
                        isNull()
                )
        ).thenThrow(
                new TenantGraphEmailService.DeltaTokenExpiredException(
                        "u1",
                        new RuntimeException()
                )
        );

        /*
         * Fresh initial synchronization starts from Graph's
         * initial delta endpoint and produces a fresh final token.
         */
        when(
                graph.getInboxDeltaPage(
                        eq("u1"),
                        isNull(),
                        eq(boundary)
                )
        ).thenReturn(
                new TenantGraphEmailService.DeltaPage(
                        List.of(),
                        null,
                        "fresh"
                )
        );

        service.syncTenant();

        /*
         * The invalid token is replaced by the new authoritative
         * delta token produced by the fresh synchronization.
         */
        assertEquals(
                "fresh",
                state.getDeltaLink()
        );

        assertNull(
                state.getDeltaNextLink()
        );

        /*
         * One save for the token reset and one for the final
         * successful fresh synchronization.
         */
        verify(
                states,
                times(2)
        ).saveAndFlush(state);

        verify(graph)
                .getInboxDeltaPage(
                        eq("u1"),
                        isNull(),
                        eq(boundary)
                );
    }

    private TenantGraphEmailService.TenantUser user() {
        return new TenantGraphEmailService.TenantUser(
                "u1",
                "User",
                "user@test",
                "user@test"
        );
    }
}