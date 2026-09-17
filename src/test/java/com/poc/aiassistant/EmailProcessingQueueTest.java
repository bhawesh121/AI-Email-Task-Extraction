package com.poc.aiassistant;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.jdbc.core.JdbcOperations;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.ExtractedTask;
import com.poc.aiassistant.dto.TaskDto;
import com.poc.aiassistant.entity.EmailProcessing;
import com.poc.aiassistant.entity.EmailProcessingStatus;
import com.poc.aiassistant.entity.TaskPriority;
import com.poc.aiassistant.repository.EmailProcessingRepository;
import com.poc.aiassistant.service.EmailProcessingService;
import com.poc.aiassistant.service.EmailTaskService;
import com.poc.aiassistant.service.SlaService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

class EmailProcessingQueueTest {

    private EmailProcessingRepository repository;
    private EmailTaskService taskService;
    private EntityManager entityManager;
    private JdbcOperations jdbc;
    private EmailProcessingService service;

    @BeforeEach
    void setUp() {
        repository = mock(EmailProcessingRepository.class);
        taskService = mock(EmailTaskService.class);
        entityManager = mock(EntityManager.class);
        jdbc = mock(JdbcOperations.class);
        service = new EmailProcessingService(
                repository,
                taskService,
                mock(SlaService.class),
                entityManager,
                jdbc,
                3,
                15,
                60,
                100,
                20,
                2880
        );
    }

    @Test
    void duplicateDiscoveryUsesDatabaseIdempotency() {
        when(jdbc.update(anyString(), any(), any(), any()))
                .thenReturn(0);

        assertFalse(service.registerPending("u1", "a@x.test", "m1"));

        verify(jdbc).update(
                contains("ON CONFLICT (mailbox_user_id, message_id) DO NOTHING"),
                eq("m1"),
                eq("u1"),
                eq("a@x.test")
        );
    }

    @Test
    void claimIsAtomicAndDoesNotIncrementAttemptBeforeCapacityIsReserved() {
        when(jdbc.query(
                anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq("u1"),
                eq(3)
        )).thenReturn(List.of(7L));

        EmailProcessing processing =
                newProcessing(7L, EmailProcessingStatus.PENDING, 0);

        when(entityManager.find(
                EmailProcessing.class,
                7L
        )).thenReturn(processing);

        EmailProcessing claimed =
                service.claimNextReady("u1", "worker-1");

        assertEquals(
                EmailProcessingStatus.PROCESSING,
                claimed.getStatus()
        );

        assertEquals(0, claimed.getAttemptCount());
        assertEquals("worker-1", claimed.getClaimedBy());
        assertNotNull(claimed.getClaimedAt());

        verify(jdbc).query(
                contains("FOR UPDATE SKIP LOCKED"),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq("u1"),
                eq(3)
        );
    }

    @Test
    void rateLimitReleaseRestoresPendingWithoutIncrementingAttempt() {
        EmailProcessing processing =
                newProcessing(7L, EmailProcessingStatus.PROCESSING, 0);

        processing.setClaimedAt(
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        processing.setClaimedBy("worker-1");
        processing.setClaimedAt(OffsetDateTime.now(ZoneOffset.UTC));

        when(entityManager.find(
                eq(EmailProcessing.class),
                eq(7L),
                eq(LockModeType.PESSIMISTIC_WRITE)
        )).thenReturn(processing);

        service.releaseClaimWithoutAttempt(
                7L,
                "worker-1"
        );

        assertEquals(
                EmailProcessingStatus.PENDING,
                processing.getStatus()
        );

        assertEquals(0, processing.getAttemptCount());
        assertNull(processing.getClaimedAt());
        assertNull(processing.getClaimedBy());
    }

    @Test
    void attemptCountChangesOnlyWhenActualProcessingStarts() {
        EmailProcessing processing =
                newProcessing(7L, EmailProcessingStatus.PROCESSING, 0);

        processing.setClaimedBy("worker-1");
        processing.setClaimedAt(OffsetDateTime.now(ZoneOffset.UTC));

        when(entityManager.find(
                eq(EmailProcessing.class),
                eq(7L),
                eq(LockModeType.PESSIMISTIC_WRITE)
        )).thenReturn(processing);

        service.markAttemptStarted(
                7L,
                "worker-1"
        );

        assertEquals(1, processing.getAttemptCount());
    }

    @Test
    void successfulTaskPersistenceAndCompletedStateUseSameServiceOperation() {
        EmailProcessing processing =
                newProcessing(7L, EmailProcessingStatus.PROCESSING, 1);

        processing.setClaimedBy("worker-1");
        processing.setClaimedAt(OffsetDateTime.now(ZoneOffset.UTC));

        when(entityManager.find(
                eq(EmailProcessing.class),
                eq(7L),
                eq(LockModeType.PESSIMISTIC_WRITE)
        )).thenReturn(processing);

        EmailDto email = email();

        List<ExtractedTask> extractedTasks =
                List.of(
                        new ExtractedTask(
                                "Do work",
                                "Description",
                                null,
                                TaskPriority.MEDIUM,
                                "Sender",
                                "sender@test"
                        )
                );

        when(taskService.persistExtractedTasksForQueue(
                email,
                extractedTasks
        )).thenReturn(List.<TaskDto>of());

        List<TaskDto> result =
                service.completeSuccessfully(
                        7L,
                        "worker-1",
                        email,
                        extractedTasks
                );

        assertTrue(result.isEmpty());

        assertEquals(
                EmailProcessingStatus.COMPLETED,
                processing.getStatus()
        );

        assertNotNull(processing.getProcessedAt());
        assertNull(processing.getClaimedAt());
        assertNull(processing.getClaimedBy());

        verify(taskService)
                .persistExtractedTasksForQueue(
                        email,
                        extractedTasks
                );

        verify(taskService, never())
                .processEmailForQueue(any());
    }

    @Test
    void transientFailureSchedulesExponentialBackoff() {
        EmailProcessing processing =
                newProcessing(7L, EmailProcessingStatus.PROCESSING, 2);

        processing.setClaimedBy("worker-1");
        processing.setClaimedAt(OffsetDateTime.now(ZoneOffset.UTC));

        when(entityManager.find(
                eq(EmailProcessing.class),
                eq(7L),
                eq(LockModeType.PESSIMISTIC_WRITE)
        )).thenReturn(processing);

        service.recordFailure(
                7L,
                "worker-1",
                new RuntimeException("temporary outage")
        );

        assertEquals(
                EmailProcessingStatus.FAILED,
                processing.getStatus()
        );

        assertNotNull(processing.getNextAttemptAt());

        assertTrue(
                processing.getNextAttemptAt()
                        .isAfter(OffsetDateTime.now(ZoneOffset.UTC))
        );

        assertTrue(
                processing.getErrorMessage()
                        .contains("temporary outage")
        );
    }

    @Test
    void completedEmailIsNotProcessedAgain() {
        EmailProcessing processing =
                newProcessing(7L, EmailProcessingStatus.COMPLETED, 1);

        when(repository.findByMailboxUserIdAndMessageId(
                "u1",
                "m1"
        )).thenReturn(java.util.Optional.of(processing));

        assertTrue(
                service.isCompleted("u1", "m1")
        );

        verify(
                taskService,
                never()
        ).processEmailForQueue(any());
    }

    @Test
    void concurrentClaimSecondWorkerGetsNoRow() {
        when(jdbc.query(
                anyString(),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq("u1"),
                eq(3)
        )).thenReturn(
                List.of(7L),
                List.of()
        );

        EmailProcessing processing =
                newProcessing(7L, EmailProcessingStatus.PENDING, 0);

        when(entityManager.find(
                EmailProcessing.class,
                7L
        )).thenReturn(processing);

        assertNotNull(
                service.claimNextReady(
                        "u1",
                        "worker-1"
                )
        );

        assertNull(
                service.claimNextReady(
                        "u1",
                        "worker-2"
                )
        );
    }

    @Test
    void maximumAttemptsMoveEmailToDeadLetter() {
        EmailProcessing processing =
                newProcessing(7L, EmailProcessingStatus.PROCESSING, 3);

        processing.setClaimedBy("worker-1");
        processing.setClaimedAt(OffsetDateTime.now(ZoneOffset.UTC));

        when(entityManager.find(
                eq(EmailProcessing.class),
                eq(7L),
                eq(LockModeType.PESSIMISTIC_WRITE)
        )).thenReturn(processing);

        service.recordFailure(
                7L,
                "worker-1",
                new RuntimeException("poison")
        );

        assertEquals(
                EmailProcessingStatus.DEAD_LETTER,
                processing.getStatus()
        );

        assertNull(
                processing.getNextAttemptAt()
        );
    }

    @Test
    void removedPendingEmailIsTerminalSkipped() {
        EmailProcessing processing =
                newProcessing(7L, EmailProcessingStatus.PENDING, 0);

        when(repository.findByMailboxUserIdAndMessageId(
                "u1",
                "m1"
        )).thenReturn(java.util.Optional.of(processing));

        assertTrue(
                service.markRemoved(
                        "u1",
                        "m1"
                )
        );

        assertEquals(
                EmailProcessingStatus.SKIPPED,
                processing.getStatus()
        );
    }

    @Test
    void removedCompletedEmailRemainsCompleted() {
        EmailProcessing processing =
                newProcessing(7L, EmailProcessingStatus.COMPLETED, 1);

        when(repository.findByMailboxUserIdAndMessageId(
                "u1",
                "m1"
        )).thenReturn(java.util.Optional.of(processing));

        assertFalse(
                service.markRemoved(
                        "u1",
                        "m1"
                )
        );

        assertEquals(
                EmailProcessingStatus.COMPLETED,
                processing.getStatus()
        );
    }

    @Test
    void staleProcessingLeaseIsReclaimed() {
        when(jdbc.update(
                anyString(),
                eq(3),
                eq(3),
                any(OffsetDateTime.class)
        )).thenReturn(2);

        assertEquals(
                2,
                service.recoverExpiredLeases()
        );

        verify(jdbc).update(
                contains(
                        "status='PROCESSING' AND claimed_at < ?"
                ),
                eq(3),
                eq(3),
                any(OffsetDateTime.class)
        );
    }

    @Test
    void readyMailboxDiscoveryIsIndependentOfNewMailArrivalOrdering() {
        when(jdbc.queryForList(
                anyString(),
                eq(String.class),
                eq(3)
        )).thenReturn(
                List.of("u1", "u2")
        );

        assertEquals(
                List.of("u1", "u2"),
                service.findReadyMailboxUserIds()
        );

        verify(jdbc).queryForList(
                contains("DISTINCT mailbox_user_id"),
                eq(String.class),
                eq(3)
        );
    }

    @Test
    void unavailableClaimedEmailBecomesSkippedWithoutRetry() {
        EmailProcessing processing =
                newProcessing(7L, EmailProcessingStatus.PROCESSING, 1);

        processing.setClaimedBy("worker-1");
        processing.setClaimedAt(OffsetDateTime.now(ZoneOffset.UTC));

        when(entityManager.find(
                eq(EmailProcessing.class),
                eq(7L),
                eq(LockModeType.PESSIMISTIC_WRITE)
        )).thenReturn(processing);

        service.markUnavailable(
                7L,
                "worker-1"
        );

        assertEquals(
                EmailProcessingStatus.SKIPPED,
                processing.getStatus()
        );

        assertNull(
                processing.getNextAttemptAt()
        );

        assertNull(
                processing.getClaimedBy()
        );
    }

    @Test
    void staleWorkerCannotCompleteAfterLeaseOwnershipChanges() {
        EmailProcessing processing =
                newProcessing(7L, EmailProcessingStatus.FAILED, 1);

        processing.setClaimedBy("worker-2");
        processing.setClaimedAt(OffsetDateTime.now(ZoneOffset.UTC));

        when(entityManager.find(
                eq(EmailProcessing.class),
                eq(7L),
                eq(LockModeType.PESSIMISTIC_WRITE)
        )).thenReturn(processing);

        assertThrows(
                EmailProcessingService.LeaseOwnershipLostException.class,
                () -> service.completeSuccessfully(
                        7L,
                        "worker-1",
                        email(),
                        List.of()
                )
        );

        verify(
                taskService,
                never()
        ).processEmailForQueue(any());
    }

    @Test
    void expiredLeaseCannotBeCompletedEvenBeforeRecoveryRuns() {
        EmailProcessing processing =
                newProcessing(7L, EmailProcessingStatus.PROCESSING, 1);

        processing.setClaimedBy("worker-1");
        processing.setClaimedAt(
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(16)
        );

        when(entityManager.find(
                eq(EmailProcessing.class),
                eq(7L),
                eq(LockModeType.PESSIMISTIC_WRITE)
        )).thenReturn(processing);

        assertThrows(
                EmailProcessingService.LeaseOwnershipLostException.class,
                () -> service.completeSuccessfully(
                        7L,
                        "worker-1",
                        email(),
                        List.of()
                )
        );

        verify(taskService, never())
                .persistExtractedTasksForQueue(any(), any());

        assertEquals(
                EmailProcessingStatus.PROCESSING,
                processing.getStatus()
        );

        assertEquals(
                "worker-1",
                processing.getClaimedBy()
        );
    }

    @Test
    void expiredLeaseAtMaximumAttemptsBecomesDeadLetter() {
        when(jdbc.update(
                anyString(),
                any(),
                any(),
                any(OffsetDateTime.class)
        )).thenReturn(1);

        assertEquals(
                1,
                service.recoverExpiredLeases()
        );

        verify(jdbc).update(
                contains(
                        "CASE WHEN attempt_count >= ? THEN 'DEAD_LETTER' ELSE 'FAILED' END"
                ),
                eq(3),
                eq(3),
                any(OffsetDateTime.class)
        );
    }

    private EmailProcessing newProcessing(
            Long id,
            EmailProcessingStatus status,
            int attempts
    ) {
        EmailProcessing p =
                new EmailProcessing();

        p.setMessageId("m1");
        p.setMailboxUserId("u1");
        p.setStatus(status);
        p.setAttemptCount(attempts);

        return p;
    }

    private EmailDto email() {
        return new EmailDto(
                "m1",
                "Subject",
                "Sender",
                "sender@test",
                "test",
                "INTERNAL",
                "2026-08-27T10:00:00Z",
                "body",
                List.of(),
                List.of(),
                "u1",
                null
        );
    }
}