package com.poc.aiassistant.service;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.poc.aiassistant.entity.Task;
import com.poc.aiassistant.entity.TaskDuplicateMatch;
import com.poc.aiassistant.entity.TaskDuplicateMatchType;
import com.poc.aiassistant.repository.TaskDuplicateMatchRepository;
import com.poc.aiassistant.repository.TaskRepository;
import com.poc.aiassistant.util.SenderNormalizer;
import com.poc.aiassistant.realtime.RealtimeEventService;
import com.poc.aiassistant.realtime.RealtimeEventType;

/**
 * Owns the sender-scoped concurrency guard and the atomic
 * "does an active duplicate already exist, or do we create one"
 * decision for a single extracted task — exact (canonical hash) and,
 * if enabled, semantic (LLM-verified paraphrase) matching.
 *
 * Why this is its own bean (not a private method on EmailTaskService):
 * the {@code @Transactional(propagation = REQUIRES_NEW)} below only
 * takes effect through Spring's proxy, which requires an external
 * call — a self-invoked private method on the same class would
 * silently run in whatever transaction the caller already has open,
 * defeating the point.
 *
 * Why REQUIRES_NEW: this method deliberately runs in its own short
 * transaction rather than joining the caller's (e.g. the queue
 * path's EmailProcessingService#completeSuccessfully transaction),
 * so that:
 *   - the advisory lock below has a small, well-defined lifetime —
 *     acquired and released with this transaction, never held across
 *     the caller's other work or any Excel I/O;
 *   - if persisting one extracted task fails, it cannot roll back
 *     sibling tasks extracted from the same email, or the queue row's
 *     completion. On retry, findActiveExactMatches recognizes
 *     already-committed tasks and returns them instead of duplicating
 *     them, so a partial commit here is safe, not just tolerated.
 *
 * IMPORTANT operational note on semantic matching: when enabled, the
 * LLM verification call(s) happen INSIDE this transaction, while the
 * advisory lock is held — per the explicit requirement that candidate
 * lookup, verification, and creation be one protected operation. That
 * means a DB connection and the lock are held for the duration of an
 * LLM network round-trip (up to litellm.task-verification.read-timeout-ms),
 * not just a few milliseconds of local SQL. Under load this is a real
 * throughput/connection-pool cost, traded deliberately for correctness
 * (no duplicate race). If this becomes a bottleneck, the fix is not to
 * drop the lock — it's to move verification to a cheaper pre-filter
 * (e.g. a fast local check) before ever entering this critical section.
 */
@Service
public class TaskPersistenceService {

    private final TaskRepository taskRepository;
    private final TaskDuplicateMatchRepository taskDuplicateMatchRepository;
    private final TaskSemanticVerificationService semanticVerificationService;
    private final EmbeddingSimilarityService embeddingSimilarityService;
    private final JdbcOperations jdbcOperations;
    private final boolean semanticDetectionEnabled;
    private final int maxSemanticCandidates;
    private final double similarityThreshold;
    private final RealtimeEventService realtimeEventService;

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TaskPersistenceService.class);

    public TaskPersistenceService(
            TaskRepository taskRepository,
            TaskDuplicateMatchRepository taskDuplicateMatchRepository,
            TaskSemanticVerificationService semanticVerificationService,
            EmbeddingSimilarityService embeddingSimilarityService,
            JdbcOperations jdbcOperations,
            @Value("${semantic-duplicate-detection.enabled:false}") boolean semanticDetectionEnabled,
            @Value("${semantic-duplicate-detection.max-candidates:3}") int maxSemanticCandidates,
            @Value("${semantic-duplicate-detection.embedding-similarity-threshold:0.75}") double similarityThreshold,
            RealtimeEventService realtimeEventService
    ) {
        this.taskRepository = taskRepository;
        this.taskDuplicateMatchRepository = taskDuplicateMatchRepository;
        this.semanticVerificationService = semanticVerificationService;
        this.embeddingSimilarityService = embeddingSimilarityService;
        this.jdbcOperations = jdbcOperations;
        this.semanticDetectionEnabled = semanticDetectionEnabled;
        this.maxSemanticCandidates = maxSemanticCandidates;
        this.similarityThreshold = similarityThreshold;
        this.realtimeEventService = realtimeEventService;
    }

    public record PersistResult(Task task, boolean created) {
    }

    /**
     * Atomically resolve one extracted task to either an existing
     * active duplicate (exact or, if enabled, semantic) or a newly
     * created Task row.
     *
     * Concurrency guarantee: two callers racing on the same
     * (sourceMailbox, normalizedSender) scope serialize on the
     * Postgres advisory lock below. The second caller blocks until
     * the first commits (or rolls back); under READ COMMITTED, once
     * unblocked it sees the first caller's committed row, so both the
     * exact and semantic candidate lookups correctly resolve to the
     * existing task instead of racing the
     * ux_tasks_active_mailbox_sender_fingerprint unique index in V15
     * (which remains a defense-in-depth backstop for the exact case
     * only — it cannot protect the semantic case, which has no fixed
     * fingerprint to constrain on).
     *
     * If normalizedSender or sourceMailbox is null (e.g. a
     * manually-created task with no email source), the lock and both
     * duplicate lookups are skipped — there is no sender scope to
     * protect, so every such task is simply created.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistResult persistExactMatchAware(
            String sourceEmailId,
            String normalizedSender,
            String sourceMailbox,
            String taskFingerprint,
            Supplier<Task> newTaskSupplier
    ) {
        boolean scoped = normalizedSender != null && sourceMailbox != null;

        // Built at most once, lazily, and reused if semantic matching
        // builds it but doesn't resolve — avoids constructing the
        // Task twice (once to compare, once to save).
        Task builtDraft = null;

        if (scoped) {
            acquireSenderScopeLock(sourceMailbox, normalizedSender);

            List<Task> existingActive = taskRepository.findActiveExactMatches(
                    normalizedSender,
                    sourceMailbox,
                    taskFingerprint
            );

            if (!existingActive.isEmpty()) {
                return resolveToExisting(
                        existingActive.get(0),
                        TaskDuplicateMatchType.EXACT,
                        "Exact canonical fingerprint match",
                        sourceEmailId,
                        sourceMailbox,
                        normalizedSender
                );
            }

            if (semanticDetectionEnabled) {
                builtDraft = newTaskSupplier.get();
                PersistResult semanticResult = trySemanticMatch(
                        sourceEmailId,
                        normalizedSender,
                        sourceMailbox,
                        builtDraft
                );
                if (semanticResult != null) {
                    return semanticResult;
                }
                // Not resolved (no recipient to scope by, no
                // candidates, or all candidates DIFFERENT) — fall
                // through and save builtDraft below.
            }
        }

        try {
            Task saved = taskRepository.save(builtDraft != null ? builtDraft : newTaskSupplier.get());

            realtimeEventService.enqueue(
                    RealtimeEventType.TASK_CREATED,
                    "TASK",
                    saved.getId().toString(),
                    java.util.Map.of(
                            "taskId", saved.getId().toString(),
                            "updatedAt", saved.getUpdatedAt().toString()
                    )
            );

            return new PersistResult(saved, true);
        } catch (DataIntegrityViolationException raceLostAtDatabase) {
            // Defense-in-depth only: with the advisory lock held above,
            // normal operation should never reach this catch block. It
            // exists for callers/paths that could not take the lock
            // (e.g. normalizedSender or sourceMailbox was null) and for
            // correctness even if the lock were ever bypassed by a
            // future code change. Re-resolve to the row that won.
            if (scoped) {
                List<Task> existingActive = taskRepository.findActiveExactMatches(
                        normalizedSender,
                        sourceMailbox,
                        taskFingerprint
                );
                if (!existingActive.isEmpty()) {
                    return resolveToExisting(
                            existingActive.get(0),
                            TaskDuplicateMatchType.EXACT,
                            "Exact canonical fingerprint match (resolved after a database race)",
                            sourceEmailId,
                            sourceMailbox,
                            normalizedSender
                    );
                }
            }
            throw raceLostAtDatabase;
        }
    }

    /**
     * Semantic (paraphrase) matching, scoped to the SAME recipient as
     * newTaskDraft (not just the same sender) against a small,
     * recency-ordered candidate set, with a cheap embedding-similarity
     * pre-filter (see EmbeddingSimilarityService) before any LLM call.
     * Returns null if this path does not resolve the decision (no
     * resolvable recipient, no candidates, or all candidates
     * below-threshold/DIFFERENT), meaning the caller should fall
     * through to plain creation using the SAME newTaskDraft instance
     * (already built, not rebuilt). Returns a PersistResult if it does
     * resolve it: a SAME verdict returns the existing task; an
     * unresolved AMBIGUOUS candidate results in a new task being
     * created anyway and flagged for review.
     */
    private PersistResult trySemanticMatch(
            String sourceEmailId,
            String normalizedSender,
            String sourceMailbox,
            Task newTaskDraft
    ) {
        String normalizedAssigneeEmail = SenderNormalizer.normalize(newTaskDraft.getAssigneeEmail());

        if (normalizedAssigneeEmail == null) {
            // No resolved recipient to scope candidates by. We
            // deliberately do NOT fall back to a sender-only scope
            // here — that would risk comparing against tasks meant
            // for a different recipient, which is the exact mistake
            // recipient-scoping exists to prevent.
            log.debug("Skipping semantic matching: no resolvable assigneeEmail on the new task");
            return null;
        }

        List<Task> candidates = taskRepository.findActiveCandidatesForSemanticMatch(
                normalizedSender,
                sourceMailbox,
                normalizedAssigneeEmail,
                PageRequest.of(0, maxSemanticCandidates, Sort.by("createdAt").descending())
        );

        if (candidates.isEmpty()) {
            return null;
        }

        Task firstAmbiguousCandidate = null;
        String firstAmbiguousReason = null;

        // Tracks the first UNAVAILABLE/MALFORMED outcome hit while
        // scanning candidates, in priority order (unavailable takes
        // precedence over malformed if both occur, since it's the
        // more common/expected case). Either one means we could not
        // rule out that this "new" task is actually a duplicate of
        // some candidate, so — per the explicit business decision —
        // we must NOT create it, rather than create-and-flag as we
        // do for a genuine AMBIGUOUS.
        TaskSemanticVerificationService.Outcome firstNonVerdictOutcome = null;
        String firstNonVerdictReason = null;
        Task firstNonVerdictCandidate = null;

        for (Task candidate : candidates) {

            double similarityScore;
            boolean forceVerification;

            try {
                similarityScore = embeddingSimilarityService.scoreTasks(
                        newTaskDraft.getTitle(),
                        newTaskDraft.getDescription(),
                        candidate.getTitle(),
                        candidate.getDescription()
                );
                forceVerification = false;
            } catch (Exception embeddingFailure) {
                // Fail OPEN: an embedding-call failure must never be
                // treated as "definitely different" — that would
                // silently reintroduce the duplicate-creation risk
                // this whole pipeline exists to prevent. Send this
                // candidate to the real verifier instead, same as if
                // it had scored above threshold. NaN is passed through
                // as an explicit "score unavailable" signal rather
                // than a fabricated number.
                log.warn(
                        "Embedding similarity call failed; failing open to LLM verification for "
                                + "candidateTaskId={}",
                        candidate.getId(),
                        embeddingFailure
                );
                similarityScore = Double.NaN;
                forceVerification = true;
            }

            if (!forceVerification && similarityScore < similarityThreshold) {
                // Logged (not audited in task_duplicate_matches — that
                // table is for actual match/review events, not every
                // skipped comparison) so the filter's behavior stays
                // visible without polluting the review queue.
                log.info(
                        "Skipping LLM verification below embedding similarity threshold: "
                                + "candidateTaskId={} score={} threshold={}",
                        candidate.getId(),
                        similarityScore,
                        similarityThreshold
                );
                continue;
            }

            TaskSemanticVerificationService.VerificationResult result =
                    semanticVerificationService.verify(
                            newTaskDraft,
                            candidate,
                            similarityScore,
                            similarityThreshold
                    );

            if (result.outcome() != TaskSemanticVerificationService.Outcome.VERDICT) {
                // The model never actually rendered a verdict for
                // this candidate — do not treat it as DIFFERENT
                // (would risk a false duplicate creation) or as a
                // genuine AMBIGUOUS (would misrepresent an
                // infrastructure/config failure as a semantic
                // judgment). Remember it and keep scanning: a LATER
                // candidate might still resolve to a genuine SAME,
                // which should win outright.
                if (firstNonVerdictOutcome == null
                        || (firstNonVerdictOutcome == TaskSemanticVerificationService.Outcome.MALFORMED
                        && result.outcome() == TaskSemanticVerificationService.Outcome.UNAVAILABLE)) {
                    firstNonVerdictOutcome = result.outcome();
                    firstNonVerdictReason = result.reason();
                    firstNonVerdictCandidate = candidate;
                }
                continue;
            }

            if (result.verdict() == TaskSemanticVerificationService.Verdict.SAME) {
                return resolveToExisting(
                        candidate,
                        TaskDuplicateMatchType.SEMANTIC_SAME,
                        result.reason(),
                        sourceEmailId,
                        sourceMailbox,
                        normalizedSender
                );
            }

            if (result.verdict() == TaskSemanticVerificationService.Verdict.AMBIGUOUS
                    && firstAmbiguousCandidate == null) {
                firstAmbiguousCandidate = candidate;
                firstAmbiguousReason = result.reason();
            }

            // DIFFERENT: keep checking the remaining candidates.
        }

        if (firstNonVerdictOutcome != null) {
            // No candidate resolved to SAME, and at least one
            // candidate's verification never produced a real answer.
            // We cannot safely rule out that this task duplicates
            // that candidate, so we must not create it here. Nothing
            // has been written for this task in this transaction
            // (no save, no match record) — it is simply left for a
            // later attempt once the model/config issue is resolved.
            log.warn(
                    "Deferring task decision instead of creating: semantic verification did "
                            + "not produce a verdict against candidateTaskId={} (outcome={}, "
                            + "reason={})",
                    firstNonVerdictCandidate.getId(),
                    firstNonVerdictOutcome,
                    firstNonVerdictReason
            );

            if (firstNonVerdictOutcome == TaskSemanticVerificationService.Outcome.MALFORMED) {
                throw new TaskVerificationMalformedException(
                        "Semantic verification returned a malformed/truncated response "
                                + "for candidateTaskId=" + firstNonVerdictCandidate.getId()
                                + ": " + firstNonVerdictReason
                );
            }

            throw new TaskVerificationUnavailableException(
                    "Semantic verification was unavailable for candidateTaskId="
                            + firstNonVerdictCandidate.getId() + ": " + firstNonVerdictReason
            );
        }

        // No candidate was judged SAME. Create the task; if one
        // candidate was genuinely AMBIGUOUS (the model DID answer,
        // and genuinely could not decide), flag it for human review
        // rather than silently creating an indistinguishable
        // duplicate.
        Task saved = taskRepository.save(newTaskDraft);

        if (firstAmbiguousCandidate != null) {
            recordMatch(
                    TaskDuplicateMatchType.SEMANTIC_AMBIGUOUS,
                    firstAmbiguousCandidate.getId(),
                    saved.getId(),
                    sourceEmailId,
                    sourceMailbox,
                    normalizedSender,
                    firstAmbiguousReason
            );
        }

        return new PersistResult(saved, true);
    }

    /**
     * Thrown from {@link #trySemanticMatch} when the model/provider
     * layer never actually answered for a candidate that couldn't be
     * ruled out (rate limit, HTTP failure, timeout, empty response).
     * Classified as {@link LlmFailureCategory#UNAVAILABLE} by
     * {@link LlmFailureClassifier} — the caller should defer the
     * whole email rather than treat this as an ordinary transient
     * failure or dead-letter it.
     */
    public static class TaskVerificationUnavailableException extends RuntimeException {
        public TaskVerificationUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Thrown from {@link #trySemanticMatch} when the model DID answer
     * but the output cannot be used given the current configuration
     * (truncated by max-tokens, invalid JSON, unrecognized result
     * value). Classified as {@link LlmFailureCategory#NON_RETRYABLE}
     * — retrying will reproduce the same failure until a human
     * changes the configuration (e.g. raises
     * litellm.task-verification.max-tokens), so the caller should
     * dead-letter with a clear reason and manual requeue path rather
     * than retry or defer automatically.
     */
    public static class TaskVerificationMalformedException extends RuntimeException {
        public TaskVerificationMalformedException(String message) {
            super(message);
        }
    }

    private PersistResult resolveToExisting(
            Task existing,
            TaskDuplicateMatchType matchType,
            String reason,
            String sourceEmailId,
            String sourceMailbox,
            String normalizedSender
    ) {
        recordMatch(
                matchType,
                existing.getId(),
                null,
                sourceEmailId,
                sourceMailbox,
                normalizedSender,
                reason
        );
        return new PersistResult(existing, false);
    }

    private void recordMatch(
            TaskDuplicateMatchType matchType,
            UUID matchedTaskId,
            UUID newTaskId,
            String sourceEmailId,
            String sourceMailbox,
            String normalizedSender,
            String reason
    ) {
        TaskDuplicateMatch match = new TaskDuplicateMatch();
        match.setMatchType(matchType);
        match.setMatchedTaskId(matchedTaskId);
        match.setNewTaskId(newTaskId);
        match.setSourceEmailId(sourceEmailId);
        match.setSourceMailbox(sourceMailbox);
        match.setNormalizedSender(normalizedSender);
        match.setMatchReason(reason);
        taskDuplicateMatchRepository.save(match);
    }

    /**
     * Transaction-scoped Postgres advisory lock (pg_advisory_xact_lock),
     * automatically released at commit or rollback of this method's
     * REQUIRES_NEW transaction. Keyed by two independently-hashed
     * 32-bit values (mailbox, sender) rather than one combined hash,
     * to reduce collision probability versus a single 32-bit key.
     *
     * A hash collision here would only cause two unrelated scopes to
     * needlessly serialize against each other (a performance cost);
     * it cannot cause an incorrect duplicate decision, because the
     * actual decision is still made by the exact/semantic lookups and
     * the unique index, not by the lock key itself.
     */
    private void acquireSenderScopeLock(String sourceMailbox, String normalizedSender) {
        jdbcOperations.query(
                "SELECT pg_advisory_xact_lock(hashtext(coalesce(?, '')), hashtext(coalesce(?, '')))",
                preparedStatement -> {
                    preparedStatement.setString(1, sourceMailbox);
                    preparedStatement.setString(2, normalizedSender);
                },
                resultSet -> null
        );
    }
}

