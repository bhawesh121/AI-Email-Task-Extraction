package com.poc.aiassistant.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.poc.aiassistant.entity.EmailSourceType;
import com.poc.aiassistant.entity.Task;
import com.poc.aiassistant.entity.TaskPriority;
import com.poc.aiassistant.entity.TaskStatus;

public interface TaskRepository
        extends JpaRepository<Task, UUID>,
                JpaSpecificationExecutor<Task> {

    /**
     * Existing lookup used by other parts of the application.
     *
     * Do not remove this method because TaskService
     * still uses it.
     */
    Optional<Task> findBySourceEmailId(
            String sourceEmailId
    );

    /**
     * New task-level idempotency lookup.
     *
     * Multiple tasks can have the same sourceEmailId,
     * but the combination of:
     *
     * sourceEmailId + taskFingerprint
     *
     * identifies one extracted task.
     */
    Optional<Task> findBySourceEmailIdAndTaskFingerprint(
            String sourceEmailId,
            String taskFingerprint
    );

    /**
     * Cross-email exact-duplicate lookup: is there already an ACTIVE
     * (non-ARCHIVED) task for this mailbox + normalized sender with
     * this exact canonical fingerprint, regardless of which email it
     * originally came from?
     *
     * This is the read-side counterpart to the
     * ux_tasks_active_mailbox_sender_fingerprint partial unique index
     * (V15): the index is the last-resort guarantee, this query is
     * what lets the application resolve to ALREADY_CREATED gracefully
     * instead of ever hitting that constraint in normal operation.
     *
     * Ordered oldest-first so that if more than one active match
     * somehow exists (e.g. pre-V15 data not yet remediated), the
     * original task is treated as canonical.
     *
     * Only matches exact (canonical-hash) duplicates. Semantic
     * paraphrase matching is a separate, later mechanism.
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.normalizedSender = :normalizedSender
          AND t.sourceMailbox = :sourceMailbox
          AND t.taskFingerprint = :taskFingerprint
          AND t.status <> com.poc.aiassistant.entity.TaskStatus.ARCHIVED
        ORDER BY t.createdAt ASC
        """)
    List<Task> findActiveExactMatches(
            @Param("normalizedSender") String normalizedSender,
            @Param("sourceMailbox") String sourceMailbox,
            @Param("taskFingerprint") String taskFingerprint
    );

    /**
     * Small candidate set for semantic (paraphrase) duplicate
     * matching, scoped to ACTIVE tasks with the SAME mailbox,
     * normalized sender, AND recipient (assigneeEmail). Recipient
     * scoping is a correctness requirement, not just a performance
     * one: a task from Alice to Bob and a similar-sounding task from
     * Alice to Carol are different tasks by definition, so they must
     * never be presented to the LLM verifier as candidates of each
     * other in the first place.
     *
     * assigneeEmail is compared case/whitespace-insensitively
     * (LOWER(TRIM(...))) since, unlike normalizedSender, it has no
     * separate persisted normalized column.
     *
     * Ordered most-recent-first and capped by the caller's Pageable,
     * since semantic verification is an LLM call per candidate and
     * must stay cheap (see TaskSemanticVerificationService / the
     * shared LlmRateLimiterService budget).
     *
     * Does not use embeddings/vector search: at this app's scale,
     * per-(sender, recipient) candidate sets are expected to be very
     * small (often 0-2) once scoped this tightly. Revisit if that
     * assumption stops holding.
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.normalizedSender = :normalizedSender
          AND t.sourceMailbox = :sourceMailbox
          AND LOWER(TRIM(t.assigneeEmail)) = :normalizedAssigneeEmail
          AND t.status <> com.poc.aiassistant.entity.TaskStatus.ARCHIVED
        ORDER BY t.createdAt DESC
        """)
    List<Task> findActiveCandidatesForSemanticMatch(
            @Param("normalizedSender") String normalizedSender,
            @Param("sourceMailbox") String sourceMailbox,
            @Param("normalizedAssigneeEmail") String normalizedAssigneeEmail,
            org.springframework.data.domain.Pageable pageable
    );

    List<Task> findByStatus(
            TaskStatus status
    );

    List<Task> findByDueDateBeforeAndStatusNot(
            LocalDate date,
            TaskStatus status
    );

    /**
     * "Overdue" is meaningless for a task that has left the active
     * workload. Used instead of findByDueDateBeforeAndStatusNot
     * wherever more than one status must be excluded (COMPLETED and
     * ARCHIVED), since the single-status derived query can't express
     * that.
     */
    List<Task> findByDueDateBeforeAndStatusNotIn(
            LocalDate date,
            List<TaskStatus> excludedStatuses
    );

    long countByStatus(
            TaskStatus status
    );

    long countByPriority(
            TaskPriority priority
    );

    long countByDueDateBeforeAndStatusNot(
            LocalDate date,
            TaskStatus status
    );

    /**
     * Tasks that still need to be written (or re-written) to the
     * shared Excel workbook. Used by the retry scheduler so a
     * failed append is never permanently lost.
     */
    List<Task> findByExcelSyncedFalse();

    @Query("""
        SELECT DISTINCT t.assignee, t.assigneeEmail
        FROM Task t
        WHERE t.assignee IS NOT NULL
          AND t.assigneeEmail IS NOT NULL
          AND TRIM(t.assigneeEmail) <> ''
        ORDER BY t.assignee
        """)
    List<Object[]> findDistinctAssignees();

    @Query("""
        SELECT DISTINCT t.sourceDomain
        FROM Task t
        WHERE t.sourceDomain IS NOT NULL
        ORDER BY t.sourceDomain
        """)
    List<String> findDistinctSourceDomains();

    @Query("""
        SELECT DISTINCT t.sourceType
        FROM Task t
        WHERE t.sourceType IS NOT NULL
        ORDER BY t.sourceType
        """)
    List<EmailSourceType> findDistinctSourceTypes();

    @Query(value = """
        SELECT COUNT(DISTINCT COALESCE(t.source_email_id, t.id::text))
        FROM tasks t
        WHERE (:status IS NULL OR t.status = :status)
          AND (:priority IS NULL OR t.priority = :priority)
        """, nativeQuery = true)
    long countDistinctTaskSources(
            @Param("status") String status,
            @Param("priority") String priority
    );

    @Query(value = """
        SELECT COALESCE(t.assignee, 'Unassigned') AS name,
               COUNT(*) AS total
        FROM tasks t
        WHERE (:status IS NULL OR t.status = :status)
          AND (:priority IS NULL OR t.priority = :priority)
        GROUP BY t.assignee
        ORDER BY COUNT(*) DESC, COALESCE(t.assignee, 'Unassigned') ASC
        """, nativeQuery = true)
    List<Object[]> findTaskCountsByAssignee(
            @Param("status") String status,
            @Param("priority") String priority
    );

    @Query(value = """
        SELECT COALESCE(t.assignee, 'Unassigned') AS name,
               SUM(CASE WHEN t.status NOT IN ('COMPLETED', 'ARCHIVED') THEN 1 ELSE 0 END) AS open_count,
               SUM(CASE WHEN t.status NOT IN ('COMPLETED', 'ARCHIVED')
                         AND t.due_date < CURRENT_DATE THEN 1 ELSE 0 END) AS overdue_count,
               SUM(CASE WHEN t.status NOT IN ('COMPLETED', 'ARCHIVED')
                         AND t.priority IN ('HIGH', 'CRITICAL') THEN 1 ELSE 0 END) AS high_priority_count,
               COUNT(*) AS total_count
        FROM tasks t
        WHERE (:status IS NULL OR t.status = :status)
          AND (:priority IS NULL OR t.priority = :priority)
        GROUP BY t.assignee
        ORDER BY open_count DESC, name ASC
        """, nativeQuery = true)
    List<Object[]> findEmployeeWorkload(
            @Param("status") String status,
            @Param("priority") String priority
    );

    @Query(value = """
        SELECT CAST(COALESCE(t.email_received_at, t.created_at) AT TIME ZONE 'UTC' AS date) AS day,
               COUNT(*) AS task_count
        FROM tasks t
        WHERE COALESCE(t.email_received_at, t.created_at) >= :fromTimestamp
          AND (:status IS NULL OR t.status = :status)
          AND (:priority IS NULL OR t.priority = :priority)
        GROUP BY CAST(COALESCE(t.email_received_at, t.created_at) AT TIME ZONE 'UTC' AS date)
        ORDER BY day ASC
        """, nativeQuery = true)
    List<Object[]> findTaskTrend(
            @Param("fromTimestamp") java.time.OffsetDateTime fromTimestamp,
            @Param("status") String status,
            @Param("priority") String priority
    );
}