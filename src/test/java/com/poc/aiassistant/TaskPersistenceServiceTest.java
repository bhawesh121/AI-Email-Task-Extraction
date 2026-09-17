package com.poc.aiassistant;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.util.ReflectionTestUtils;

import com.poc.aiassistant.entity.Task;
import com.poc.aiassistant.entity.TaskDuplicateMatch;
import com.poc.aiassistant.entity.TaskDuplicateMatchType;
import com.poc.aiassistant.repository.TaskDuplicateMatchRepository;
import com.poc.aiassistant.repository.TaskRepository;
import com.poc.aiassistant.service.EmbeddingSimilarityService;
import com.poc.aiassistant.service.TaskPersistenceService;
import com.poc.aiassistant.service.TaskSemanticVerificationService;
import com.poc.aiassistant.service.TaskSemanticVerificationService.Verdict;
import com.poc.aiassistant.service.TaskSemanticVerificationService.VerificationResult;

/**
 * Sender+concurrency (Stage 2) and semantic matching (Stage 3),
 * including receiver-scoping and the local similarity pre-filter
 * added afterward.
 *
 * These are unit tests against mocks, not an integration test
 * against real PostgreSQL: they verify that the code issues the
 * advisory-lock call, the exact/semantic lookups, and the audit
 * writes in the right order and reacts to their results correctly.
 * They do NOT verify actual cross-transaction blocking behavior,
 * which can only be observed against a real Postgres instance with
 * two concurrent connections (see the limitations note in the final
 * summary).
 */
class TaskPersistenceServiceTest {

    private static final double THRESHOLD = 0.15;

    @SuppressWarnings("unchecked")
    private static void stubLockNoOp(JdbcOperations jdbc) {
        when(jdbc.query(
                contains("pg_advisory_xact_lock"),
                any(PreparedStatementSetter.class),
                any(ResultSetExtractor.class)
        )).thenReturn(null);
    }

    private static TaskDuplicateMatchRepository mockAuditRepo() {
        TaskDuplicateMatchRepository repo = mock(TaskDuplicateMatchRepository.class);
        when(repo.save(any(TaskDuplicateMatch.class))).thenAnswer(inv -> inv.getArgument(0));
        return repo;
    }

    private static Task stubJpaPersistenceState(Task task) {
        if (task.getId() == null) {
            task.setId(UUID.randomUUID());
        }

        if (task.getCreatedAt() == null) {
            ReflectionTestUtils.setField(
                    task,
                    "createdAt",
                    java.time.OffsetDateTime.now()
            );
        }

        if (task.getUpdatedAt() == null) {
            ReflectionTestUtils.setField(
                    task,
                    "updatedAt",
                    java.time.OffsetDateTime.now()
            );
        }

        return task;
    }

    private static TaskPersistenceService serviceWithSemanticDisabled(
            TaskRepository tasks, TaskDuplicateMatchRepository audit, JdbcOperations jdbc
    ) {
        return new TaskPersistenceService(
                tasks, audit,
                mock(TaskSemanticVerificationService.class),
                mock(EmbeddingSimilarityService.class),
                jdbc, false, 3, THRESHOLD,
                mock(com.poc.aiassistant.realtime.RealtimeEventService.class)
        );
    }

    private static TaskPersistenceService serviceWithSemanticEnabled(
            TaskRepository tasks, TaskDuplicateMatchRepository audit,
            TaskSemanticVerificationService verifier, EmbeddingSimilarityService similarity,
            JdbcOperations jdbc
    ) {
        return new TaskPersistenceService(
                tasks, audit, verifier, similarity, jdbc, true, 3, THRESHOLD,
                mock(com.poc.aiassistant.realtime.RealtimeEventService.class)
        );
    }

    private static Task taskWithRecipient(String assigneeEmail) {
        Task task = new Task();
        task.setAssigneeEmail(assigneeEmail);
        // Realistic non-null content: matters beyond realism here —
        // it's what exposed a real bug where anyString() silently
        // failed to match a null-argument call and masked several
        // tests (see the EmbeddingSimilarityService stubs below).
        task.setTitle("Send contract to John");
        task.setDescription("Please send the signed contract");
        return task;
    }

    private static Task candidateTask() {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle("Send the contract to John");
        task.setDescription("Please forward the signed agreement");
        return task;
    }

    // ---- Exact matching / concurrency (unchanged behavior) ----

    @Test
    void acquiresAdvisoryLockBeforeCheckingForActiveDuplicates() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        when(tasks.findActiveExactMatches("alice@company.com", "mailboxA", "fp-1"))
                .thenReturn(List.of());
        when(tasks.save(any(Task.class))).thenAnswer(inv -> stubJpaPersistenceState(inv.getArgument(0)));

        TaskPersistenceService service = serviceWithSemanticDisabled(tasks, audit, jdbc);

        Task newTask = new Task();
        TaskPersistenceService.PersistResult result = service.persistExactMatchAware(
                "email-1", "alice@company.com", "mailboxA", "fp-1", () -> newTask
        );

        assertTrue(result.created());
        assertSame(newTask, result.task());

        verify(jdbc).query(
                contains("pg_advisory_xact_lock"),
                any(PreparedStatementSetter.class),
                any(ResultSetExtractor.class)
        );
        verify(tasks).findActiveExactMatches("alice@company.com", "mailboxA", "fp-1");
        verify(tasks).save(newTask);
        verify(audit, never()).save(any());
    }

    @Test
    void exactMatchIsRecordedInTheAuditTrail() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task existing = new Task();
        existing.setId(UUID.randomUUID());

        when(tasks.findActiveExactMatches("alice@company.com", "mailboxA", "fp-1"))
                .thenReturn(List.of(existing));

        TaskPersistenceService service = serviceWithSemanticDisabled(tasks, audit, jdbc);

        TaskPersistenceService.PersistResult result = service.persistExactMatchAware(
                "email-2", "alice@company.com", "mailboxA", "fp-1",
                () -> { throw new AssertionError("supplier must not be invoked when an active duplicate exists"); }
        );

        assertFalse(result.created());
        assertSame(existing, result.task());
        verify(tasks, never()).save(any(Task.class));

        ArgumentCaptor<TaskDuplicateMatch> captured = ArgumentCaptor.forClass(TaskDuplicateMatch.class);
        verify(audit).save(captured.capture());
        assertEquals(TaskDuplicateMatchType.EXACT, captured.getValue().getMatchType());
        assertEquals(existing.getId(), captured.getValue().getMatchedTaskId());
        assertNull(captured.getValue().getNewTaskId());
        assertEquals("email-2", captured.getValue().getSourceEmailId());
    }

    @Test
    void skipsLockAndLookupWhenSenderOrMailboxIsUnscoped() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        JdbcOperations jdbc = mock(JdbcOperations.class);

        when(tasks.save(any(Task.class))).thenAnswer(inv -> stubJpaPersistenceState(inv.getArgument(0)));

        TaskPersistenceService service = serviceWithSemanticDisabled(tasks, audit, jdbc);

        Task newTask = new Task();
        TaskPersistenceService.PersistResult result = service.persistExactMatchAware(
                "email-3", null, null, "fp-1", () -> newTask
        );

        assertTrue(result.created());
        verify(jdbc, never()).query(
                contains("pg_advisory_xact_lock"),
                any(PreparedStatementSetter.class),
                any(ResultSetExtractor.class)
        );
        verify(tasks, never()).findActiveExactMatches(any(), any(), any());
        verify(tasks).save(newTask);
    }

    @Test
    void fallsBackToExistingRowWhenInsertLosesRaceAtTheDatabase() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task winner = new Task();

        when(tasks.findActiveExactMatches("alice@company.com", "mailboxA", "fp-1"))
                .thenReturn(List.of())
                .thenReturn(List.of(winner));
        when(tasks.save(any(Task.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        TaskPersistenceService service = serviceWithSemanticDisabled(tasks, audit, jdbc);

        TaskPersistenceService.PersistResult result = service.persistExactMatchAware(
                "email-4", "alice@company.com", "mailboxA", "fp-1", Task::new
        );

        assertFalse(result.created());
        assertSame(winner, result.task());
    }

    @Test
    void rethrowsWhenRaceCannotBeResolvedByReQuerying() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        when(tasks.findActiveExactMatches("alice@company.com", "mailboxA", "fp-1"))
                .thenReturn(List.of());
        when(tasks.save(any(Task.class)))
                .thenThrow(new DataIntegrityViolationException("some other constraint"));

        TaskPersistenceService service = serviceWithSemanticDisabled(tasks, audit, jdbc);

        assertThrows(DataIntegrityViolationException.class, () ->
                service.persistExactMatchAware(
                        "email-5", "alice@company.com", "mailboxA", "fp-1", Task::new
                )
        );
    }

    // ---- Semantic matching (enabled): feature flag / recipient gating ----

    @Test
    void semanticMatchingIsSkippedWhenFeatureFlagIsDisabled() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.save(any(Task.class))).thenAnswer(inv -> stubJpaPersistenceState(inv.getArgument(0)));

        TaskPersistenceService service = serviceWithSemanticDisabled(tasks, audit, jdbc);

        Task newTask = taskWithRecipient("bob@company.com");
        service.persistExactMatchAware("email-6", "alice@company.com", "mailboxA", "fp-1", () -> newTask);

        verify(tasks, never()).findActiveCandidatesForSemanticMatch(any(), any(), any(), any());
    }

    @Test
    void semanticMatchingIsSkippedWhenNewTaskHasNoResolvedRecipient() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.save(any(Task.class))).thenAnswer(inv -> stubJpaPersistenceState(inv.getArgument(0)));

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        // No assigneeEmail set at all -> nothing to scope candidates by.
        Task newTask = new Task();
        TaskPersistenceService.PersistResult result = service.persistExactMatchAware(
                "email-6b", "alice@company.com", "mailboxA", "fp-1", () -> newTask
        );

        assertTrue(result.created());
        verify(tasks, never()).findActiveCandidatesForSemanticMatch(any(), any(), any(), any());
        // We must not build the task twice.
        verify(tasks).save(newTask);
    }

    @Test
    void candidateQueryIsScopedToTheSameRecipientAsTheNewTask() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.findActiveCandidatesForSemanticMatch(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(tasks.save(any(Task.class))).thenAnswer(inv -> stubJpaPersistenceState(inv.getArgument(0)));

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        Task newTask = taskWithRecipient("  Bob@Company.com  ");
        service.persistExactMatchAware("email-6c", "alice@company.com", "mailboxA", "fp-1", () -> newTask);

        verify(tasks).findActiveCandidatesForSemanticMatch(
                eq("alice@company.com"), eq("mailboxA"), eq("bob@company.com"), any(Pageable.class)
        );
    }

    // ---- Semantic matching: similarity pre-filter ----

    @Test
    void candidateBelowSimilarityThresholdIsNeverSentToTheLlm() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task candidate = candidateTask();

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.findActiveCandidatesForSemanticMatch(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(similarity.scoreTasks(any(), any(), any(), any()))
                .thenReturn(0.01); // well below THRESHOLD
        when(tasks.save(any(Task.class))).thenAnswer(inv -> stubJpaPersistenceState(inv.getArgument(0)));

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        Task newTask = taskWithRecipient("bob@company.com");
        TaskPersistenceService.PersistResult result = service.persistExactMatchAware(
                "email-7a", "alice@company.com", "mailboxA", "fp-1", () -> newTask
        );

        assertTrue(result.created());
        verify(verifier, never()).verify(any(), any(), anyDouble(), anyDouble());
        verify(audit, never()).save(any());
    }

    @Test
    void embeddingCallFailureFailsOpenToLlmVerificationRatherThanSkippingIt() {
        // The core new guarantee: an embedding-service exception must
        // NEVER be treated as "definitely different". It must reach
        // the real verifier exactly as if it had scored above
        // threshold, regardless of how low similarityThreshold is set.
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task candidate = candidateTask();

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.findActiveCandidatesForSemanticMatch(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(similarity.scoreTasks(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Ollama connection refused"));
        when(verifier.verify(any(Task.class), eq(candidate), anyDouble(), anyDouble()))
                .thenReturn(new VerificationResult(Verdict.SAME, "same intent"));

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        Task newTask = taskWithRecipient("bob@company.com");
        TaskPersistenceService.PersistResult result = service.persistExactMatchAware(
                "email-7b", "alice@company.com", "mailboxA", "fp-1b", () -> newTask
        );

        assertFalse(result.created());
        assertSame(candidate, result.task());
        verify(verifier).verify(any(Task.class), eq(candidate), anyDouble(), anyDouble());
    }

    @Test
    void embeddingCallFailurePassesNaNAsScoreNotAFabricatedNumber() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task candidate = candidateTask();

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.findActiveCandidatesForSemanticMatch(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(similarity.scoreTasks(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("embedding response malformed"));
        when(verifier.verify(any(Task.class), eq(candidate), anyDouble(), anyDouble()))
                .thenReturn(new VerificationResult(Verdict.DIFFERENT, "unrelated"));
        when(tasks.save(any(Task.class))).thenAnswer(inv -> stubJpaPersistenceState(inv.getArgument(0)));

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        Task newTask = taskWithRecipient("bob@company.com");
        service.persistExactMatchAware(
                "email-7c", "alice@company.com", "mailboxA", "fp-1c", () -> newTask
        );

        org.mockito.ArgumentCaptor<Double> scoreCaptor = org.mockito.ArgumentCaptor.forClass(Double.class);
        verify(verifier).verify(any(Task.class), eq(candidate), scoreCaptor.capture(), anyDouble());
        assertTrue(Double.isNaN(scoreCaptor.getValue()), "score passed to verifier must be NaN, not a made-up number");
    }

    @Test
    void candidateAtOrAboveThresholdIsSentToTheLlmWithTheScoreAsContext() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task candidate = candidateTask();

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.findActiveCandidatesForSemanticMatch(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(similarity.scoreTasks(any(), any(), any(), any()))
                .thenReturn(0.5);
        when(verifier.verify(any(Task.class), eq(candidate), eq(0.5), eq(THRESHOLD)))
                .thenReturn(new VerificationResult(Verdict.SAME, "Same intent"));

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        Task newTask = taskWithRecipient("bob@company.com");
        TaskPersistenceService.PersistResult result = service.persistExactMatchAware(
                "email-7b", "alice@company.com", "mailboxA", "fp-1", () -> newTask
        );

        assertFalse(result.created());
        assertSame(candidate, result.task());
        verify(verifier).verify(any(Task.class), eq(candidate), eq(0.5), eq(THRESHOLD));
    }

    // ---- Semantic matching: SAME / DIFFERENT / AMBIGUOUS outcomes ----

    @Test
    void semanticSameVerdictReturnsExistingCandidateAndRecordsAudit() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task candidate = candidateTask();

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.findActiveCandidatesForSemanticMatch(
                eq("alice@company.com"), eq("mailboxA"), eq("bob@company.com"), any(Pageable.class)
        )).thenReturn(List.of(candidate));
        when(similarity.scoreTasks(any(), any(), any(), any())).thenReturn(0.9);
        when(verifier.verify(any(Task.class), eq(candidate), anyDouble(), anyDouble()))
                .thenReturn(new VerificationResult(Verdict.SAME, "Same intent, different wording"));

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        Task newTask = taskWithRecipient("bob@company.com");
        TaskPersistenceService.PersistResult result = service.persistExactMatchAware(
                "email-8", "alice@company.com", "mailboxA", "fp-2", () -> newTask
        );

        assertFalse(result.created());
        assertSame(candidate, result.task());
        verify(tasks, never()).save(any(Task.class));

        ArgumentCaptor<TaskDuplicateMatch> captured = ArgumentCaptor.forClass(TaskDuplicateMatch.class);
        verify(audit).save(captured.capture());
        assertEquals(TaskDuplicateMatchType.SEMANTIC_SAME, captured.getValue().getMatchType());
        assertEquals(candidate.getId(), captured.getValue().getMatchedTaskId());
        assertNull(captured.getValue().getNewTaskId());
    }

    @Test
    void semanticDifferentVerdictCreatesNewTaskWithoutAuditEntry() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task candidate = candidateTask();

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.findActiveCandidatesForSemanticMatch(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(similarity.scoreTasks(any(), any(), any(), any())).thenReturn(0.6);
        when(verifier.verify(any(Task.class), any(Task.class), anyDouble(), anyDouble()))
                .thenReturn(new VerificationResult(Verdict.DIFFERENT, "Different invoice numbers"));
        when(tasks.save(any(Task.class))).thenAnswer(inv -> stubJpaPersistenceState(inv.getArgument(0)));

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        Task newTask = taskWithRecipient("bob@company.com");
        TaskPersistenceService.PersistResult result = service.persistExactMatchAware(
                "email-9", "alice@company.com", "mailboxA", "fp-3", () -> newTask
        );

        assertTrue(result.created());
        assertSame(newTask, result.task());
        verify(audit, never()).save(any());
    }

    @Test
    void semanticAmbiguousVerdictCreatesTaskAndFlagsItForReview() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task candidate = candidateTask();

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.findActiveCandidatesForSemanticMatch(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(similarity.scoreTasks(any(), any(), any(), any())).thenReturn(0.4);
        when(verifier.verify(any(Task.class), any(Task.class), anyDouble(), anyDouble()))
                .thenReturn(new VerificationResult(Verdict.AMBIGUOUS, "Could not confirm the due date match"));

        Task newTask = taskWithRecipient("bob@company.com");
        newTask.setId(UUID.randomUUID());
        when(tasks.save(any(Task.class))).thenReturn(newTask);

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        TaskPersistenceService.PersistResult result = service.persistExactMatchAware(
                "email-10", "alice@company.com", "mailboxA", "fp-4", () -> newTask
        );

        // AMBIGUOUS must never suppress: a new task IS created.
        assertTrue(result.created());
        assertSame(newTask, result.task());

        ArgumentCaptor<TaskDuplicateMatch> captured = ArgumentCaptor.forClass(TaskDuplicateMatch.class);
        verify(audit).save(captured.capture());
        assertEquals(TaskDuplicateMatchType.SEMANTIC_AMBIGUOUS, captured.getValue().getMatchType());
        assertEquals(candidate.getId(), captured.getValue().getMatchedTaskId());
        assertEquals(newTask.getId(), captured.getValue().getNewTaskId());
    }

    @Test
    void aLaterSameVerdictShortCircuitsRemainingCandidateChecks() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task candidateA = candidateTask();
        Task candidateB = candidateTask();

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.findActiveCandidatesForSemanticMatch(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(candidateA, candidateB));
        when(similarity.scoreTasks(any(), any(), any(), any())).thenReturn(0.9);
        when(verifier.verify(any(Task.class), eq(candidateA), anyDouble(), anyDouble()))
                .thenReturn(new VerificationResult(Verdict.SAME, "match"));

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        Task newTask = taskWithRecipient("bob@company.com");
        TaskPersistenceService.PersistResult result = service.persistExactMatchAware(
                "email-11", "alice@company.com", "mailboxA", "fp-5", () -> newTask
        );

        // Positive assertion first: the short-circuit claim is only
        // meaningful if we confirm the LLM WAS actually reached and
        // resolved things via candidateA, not that it was skipped
        // entirely (e.g. by the threshold filter) for an unrelated
        // reason — a bug this test previously missed.
        assertFalse(result.created());
        assertSame(candidateA, result.task());
        verify(verifier).verify(any(Task.class), eq(candidateA), anyDouble(), anyDouble());
        verify(verifier, never()).verify(any(Task.class), eq(candidateB), anyDouble(), anyDouble());
    }

    // ---- UNAVAILABLE / MALFORMED outcomes: must defer, never create ----

    @Test
    void unavailableVerificationDoesNotCreateTaskOrAuditEntryAndThrowsUnavailable() {
        // This is the core requirement from the redesign: during a
        // provider outage, we must NOT create a potentially-duplicate
        // task. Nothing should be written — no task, no audit row —
        // and the caller must be able to tell this apart from an
        // ordinary transient failure or a genuine AMBIGUOUS verdict
        // via the exception type.
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task candidate = candidateTask();

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.findActiveCandidatesForSemanticMatch(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(similarity.scoreTasks(any(), any(), any(), any())).thenReturn(0.6);
        when(verifier.verify(any(Task.class), eq(candidate), anyDouble(), anyDouble()))
                .thenReturn(TaskSemanticVerificationService.VerificationResult.unavailable(
                        "verification call failed"
                ));

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        Task newTask = taskWithRecipient("bob@company.com");

        assertThrows(
                TaskPersistenceService.TaskVerificationUnavailableException.class,
                () -> service.persistExactMatchAware(
                        "email-12", "alice@company.com", "mailboxA", "fp-6", () -> newTask
                )
        );

        verify(tasks, never()).save(any(Task.class));
        verify(audit, never()).save(any());
    }

    @Test
    void malformedVerificationDoesNotCreateTaskAndThrowsMalformedDistinctFromUnavailable() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task candidate = candidateTask();

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.findActiveCandidatesForSemanticMatch(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(similarity.scoreTasks(any(), any(), any(), any())).thenReturn(0.6);
        when(verifier.verify(any(Task.class), eq(candidate), anyDouble(), anyDouble()))
                .thenReturn(TaskSemanticVerificationService.VerificationResult.malformed(
                        "verifier response truncated by max_tokens"
                ));

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        Task newTask = taskWithRecipient("bob@company.com");

        assertThrows(
                TaskPersistenceService.TaskVerificationMalformedException.class,
                () -> service.persistExactMatchAware(
                        "email-13", "alice@company.com", "mailboxA", "fp-7", () -> newTask
                )
        );

        verify(tasks, never()).save(any(Task.class));
        verify(audit, never()).save(any());
    }

    @Test
    void aLaterCandidateResolvingSameStillWinsOverAnEarlierUnavailableOutcome() {
        // If a LATER candidate resolves to a genuine SAME, that must
        // take priority over an earlier candidate's UNAVAILABLE
        // outcome — we found a real match, so there is no ambiguity
        // left to defer.
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task candidateA = candidateTask();
        Task candidateB = candidateTask();

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.findActiveCandidatesForSemanticMatch(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(candidateA, candidateB));
        when(similarity.scoreTasks(any(), any(), any(), any())).thenReturn(0.9);
        when(verifier.verify(any(Task.class), eq(candidateA), anyDouble(), anyDouble()))
                .thenReturn(TaskSemanticVerificationService.VerificationResult.unavailable("down"));
        when(verifier.verify(any(Task.class), eq(candidateB), anyDouble(), anyDouble()))
                .thenReturn(new VerificationResult(Verdict.SAME, "same intent"));

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        Task newTask = taskWithRecipient("bob@company.com");
        TaskPersistenceService.PersistResult result = service.persistExactMatchAware(
                "email-14", "alice@company.com", "mailboxA", "fp-8", () -> newTask
        );

        assertFalse(result.created());
        assertSame(candidateB, result.task());
        verify(tasks, never()).save(any(Task.class));
    }

    @Test
    void unavailableOutcomeTakesPrecedenceOverAGenuineAmbiguousElsewhereInTheScan() {
        // Conservative-by-design: if ANY candidate's verification was
        // unavailable and no candidate resolved to SAME, we defer —
        // even if a DIFFERENT candidate was genuinely AMBIGUOUS. We
        // cannot be confident there's no duplicate, so we must not
        // create the task, per the explicit business decision.
        TaskRepository tasks = mock(TaskRepository.class);
        TaskDuplicateMatchRepository audit = mockAuditRepo();
        TaskSemanticVerificationService verifier = mock(TaskSemanticVerificationService.class);
        EmbeddingSimilarityService similarity = mock(EmbeddingSimilarityService.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        stubLockNoOp(jdbc);

        Task candidateA = candidateTask();
        Task candidateB = candidateTask();

        when(tasks.findActiveExactMatches(any(), any(), any())).thenReturn(List.of());
        when(tasks.findActiveCandidatesForSemanticMatch(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(candidateA, candidateB));
        when(similarity.scoreTasks(any(), any(), any(), any())).thenReturn(0.5);
        when(verifier.verify(any(Task.class), eq(candidateA), anyDouble(), anyDouble()))
                .thenReturn(new VerificationResult(Verdict.AMBIGUOUS, "genuinely unclear"));
        when(verifier.verify(any(Task.class), eq(candidateB), anyDouble(), anyDouble()))
                .thenReturn(TaskSemanticVerificationService.VerificationResult.unavailable("down"));

        TaskPersistenceService service = serviceWithSemanticEnabled(tasks, audit, verifier, similarity, jdbc);

        Task newTask = taskWithRecipient("bob@company.com");

        assertThrows(
                TaskPersistenceService.TaskVerificationUnavailableException.class,
                () -> service.persistExactMatchAware(
                        "email-15", "alice@company.com", "mailboxA", "fp-9", () -> newTask
                )
        );

        verify(tasks, never()).save(any(Task.class));
        verify(audit, never()).save(any());
    }
}