package com.poc.aiassistant.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.poc.aiassistant.dto.EmployeeWorkloadDto;
import com.poc.aiassistant.dto.TaskAssigneeCountDto;
import com.poc.aiassistant.dto.TaskDashboardResponse;
import com.poc.aiassistant.dto.TaskDashboardTaskDto;
import com.poc.aiassistant.dto.TaskDto;
import com.poc.aiassistant.dto.TaskTrendPointDto;
import com.poc.aiassistant.dto.TaskFilterOptionsResponse;
import com.poc.aiassistant.dto.TaskPageResponse;
import com.poc.aiassistant.dto.TaskListItemDto;
import com.poc.aiassistant.dto.TaskSummaryResponse;
import com.poc.aiassistant.entity.EmailSourceType;
import com.poc.aiassistant.entity.Task;
import com.poc.aiassistant.entity.TaskPriority;
import com.poc.aiassistant.entity.TaskStatus;
import com.poc.aiassistant.repository.EmployeeRepository;
import com.poc.aiassistant.repository.TaskRepository;
import com.poc.aiassistant.util.SenderNormalizer;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

@Service
@Transactional
public class TaskService {

    private final TaskRepository taskRepository;
    private final EmployeeRepository employeeRepository;
    
    public TaskService(
        TaskRepository taskRepository,
        EmployeeRepository employeeRepository
    ) {
        this.taskRepository = taskRepository;
        this.employeeRepository = employeeRepository;
    }

    private Specification<Task> buildTaskSpecification(
                TaskStatus status,
                TaskPriority priority,
                String assigneeEmail,
                String sourceDomain,
                EmailSourceType sourceType
        ) {

        return buildTaskSpecification(
                status,
                priority,
                assigneeEmail,
                sourceDomain,
                sourceType,
                null,
                null,
                null
        );
    }

    /**
     * Builds the task-list specification used by the new paginated task API.
     *
     * Existing callers keep using the simpler overload above, so their
     * behavior remains unchanged.
     */
    private Specification<Task> buildTaskSpecification(
            TaskStatus status,
            TaskPriority priority,
            String assigneeEmail,
            String sourceDomain,
            EmailSourceType sourceType,
            String search,
            String attention,
            String sort
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }

            if (priority != null) {
                predicates.add(criteriaBuilder.equal(root.get("priority"), priority));
            }

            if (assigneeEmail != null && !assigneeEmail.isBlank()) {
                predicates.add(
                        criteriaBuilder.equal(
                                criteriaBuilder.lower(root.get("assigneeEmail")),
                                assigneeEmail.trim().toLowerCase()
                        )
                );
            }

            if (sourceDomain != null && !sourceDomain.isBlank()) {
                predicates.add(
                        criteriaBuilder.equal(
                                criteriaBuilder.lower(root.get("sourceDomain")),
                                sourceDomain.trim().toLowerCase()
                        )
                );
            }

            if (sourceType != null) {
                predicates.add(criteriaBuilder.equal(root.get("sourceType"), sourceType));
            }

            if (search != null && !search.isBlank()) {
                List<String> searchTokens = normalizeSearchTokens(search);

                if (searchTokens.isEmpty()) {
                    // Backend search has a minimum useful token length of two
                    // characters. Returning no matches is safer than turning
                    // an unsupported one-character query into an unfiltered
                    // full-table scan.
                    predicates.add(criteriaBuilder.disjunction());
                } else {
                    Expression<String> normalizedSearchText = buildNormalizedSearchText(
                            root,
                            criteriaBuilder
                    );

                    // Token-prefix semantics: each search token must match the
                    // beginning of at least one word in the searchable text.
                    // This avoids substring surprises such as "au" matching
                    // the middle of "launch" while still matching "audit".
                    for (String token : searchTokens) {
                        predicates.add(
                                criteriaBuilder.like(
                                        normalizedSearchText,
                                        "% " + token + "%"
                                )
                        );
                    }
                }
            }

            if ("overdue".equalsIgnoreCase(attention)) {
                predicates.add(
                        criteriaBuilder.and(
                                criteriaBuilder.lessThan(root.get("dueDate"), LocalDate.now()),
                                criteriaBuilder.not(
                                        root.get("status").in(
                                                TaskStatus.COMPLETED,
                                                TaskStatus.ARCHIVED
                                        )
                                )
                        )
                );
            } else if ("priority".equalsIgnoreCase(attention)) {
                predicates.add(
                        criteriaBuilder.and(
                                criteriaBuilder.not(
                                        root.get("status").in(
                                                TaskStatus.COMPLETED,
                                                TaskStatus.ARCHIVED
                                        )
                                ),
                                root.get("priority").in(
                                        TaskPriority.HIGH,
                                        TaskPriority.CRITICAL
                                )
                        )
                );
            }

            // Sorting belongs to the content query only. The count query has
            // a numeric result type and must not receive ORDER BY clauses.
            if (sort != null && !sort.isBlank() && !isCountQuery(query)) {
                applyTaskPageOrdering(root, query, criteriaBuilder, sort);
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static final Pattern SEARCH_TOKEN_SEPARATOR =
            Pattern.compile("[^\\p{L}\\p{N}]+");

    private static List<String> normalizeSearchTokens(String search) {
        return SEARCH_TOKEN_SEPARATOR
                .splitAsStream(search.trim().toLowerCase(Locale.ROOT))
                .filter(token -> token.length() >= 2)
                .distinct()
                .toList();
    }

    /**
     * Mirrors the PostgreSQL expression used by V25's trigram expression
     * index. Punctuation is normalized to spaces and the complete searchable
     * surface is lower-cased, allowing token-prefix searches to use the
     * expression index instead of scanning the raw TEXT columns independently.
     */
    private Expression<String> buildNormalizedSearchText(
            jakarta.persistence.criteria.Root<Task> root,
            jakarta.persistence.criteria.CriteriaBuilder criteriaBuilder
    ) {
        Expression<String> title =
                criteriaBuilder.coalesce(
                        root.get("title"),
                        ""
                );

        Expression<String> assignee =
                criteriaBuilder.coalesce(
                        root.get("assignee"),
                        ""
                );

        Expression<String> description =
                criteriaBuilder.coalesce(
                        root.get("description"),
                        ""
                );

        Expression<String> sourceSubject =
                criteriaBuilder.coalesce(
                        root.get("sourceSubject"),
                        ""
                );

        Expression<String> combined =
                criteriaBuilder.concat(
                        criteriaBuilder.concat(
                                criteriaBuilder.concat(
                                        title,
                                        " "
                                ),
                                assignee
                        ),
                        criteriaBuilder.concat(
                                criteriaBuilder.concat(
                                        " ",
                                        description
                                ),
                                criteriaBuilder.concat(
                                        " ",
                                        sourceSubject
                                )
                        )
                );

        Expression<String> normalized =
                criteriaBuilder.function(
                        "regexp_replace",
                        String.class,
                        combined,
                        criteriaBuilder.literal(
                                "[^[:alnum:]]+"
                        ),
                        criteriaBuilder.literal(" "),
                        criteriaBuilder.literal("g")
                );

        return criteriaBuilder.lower(
                criteriaBuilder.concat(
                        criteriaBuilder.concat(
                                " ",
                                normalized
                        ),
                        " "
                )
        );
    }

    private boolean isCountQuery(jakarta.persistence.criteria.CriteriaQuery<?> query) {
        Class<?> resultType = query.getResultType();
        return resultType == Long.class || resultType == long.class;
    }

    private void applyTaskPageOrdering(
            jakarta.persistence.criteria.Root<Task> root,
            jakarta.persistence.criteria.CriteriaQuery<?> query,
            jakarta.persistence.criteria.CriteriaBuilder criteriaBuilder,
            String sort
    ) {
        String normalizedSort = sort.trim().toLowerCase();
        Expression<?> id = root.get("id");

        switch (normalizedSort) {
            case "due-asc" -> {
                query.orderBy(
                        criteriaBuilder.asc(
                                criteriaBuilder.selectCase()
                                        .when(criteriaBuilder.isNull(root.get("dueDate")), 1)
                                        .otherwise(0)
                        ),
                        criteriaBuilder.asc(root.get("dueDate")),
                        criteriaBuilder.asc(id)
                );
            }
            case "due-desc" -> {
                query.orderBy(
                        criteriaBuilder.asc(
                                criteriaBuilder.selectCase()
                                        .when(criteriaBuilder.isNull(root.get("dueDate")), 0)
                                        .otherwise(1)
                        ),
                        criteriaBuilder.desc(root.get("dueDate")),
                        criteriaBuilder.desc(id)
                );
            }
            case "received-asc", "received-desc" -> {
                Expression<?> receivedAt =
                        criteriaBuilder.coalesce(
                                root.get("emailReceivedAt"),
                                root.get("createdAt")
                        );
                boolean ascending = "received-asc".equals(normalizedSort);
                query.orderBy(
                        ascending
                                ? criteriaBuilder.asc(receivedAt)
                                : criteriaBuilder.desc(receivedAt),
                        ascending
                                ? criteriaBuilder.asc(id)
                                : criteriaBuilder.desc(id)
                );
            }
            case "priority" -> {
                Expression<Integer> priorityRank =
                        criteriaBuilder.<Integer>selectCase()
                                .when(criteriaBuilder.equal(root.get("priority"), TaskPriority.CRITICAL), 3)
                                .when(criteriaBuilder.equal(root.get("priority"), TaskPriority.HIGH), 2)
                                .when(criteriaBuilder.equal(root.get("priority"), TaskPriority.MEDIUM), 1)
                                .otherwise(0);
                query.orderBy(
                        criteriaBuilder.desc(priorityRank),
                        criteriaBuilder.desc(id)
                );
            }
            case "recent" -> query.orderBy(
                    criteriaBuilder.desc(root.get("updatedAt")),
                    criteriaBuilder.desc(id)
            );
            default -> query.orderBy(
                    criteriaBuilder.desc(
                            criteriaBuilder.coalesce(
                                    root.get("emailReceivedAt"),
                                    root.get("createdAt")
                            )
                    ),
                    criteriaBuilder.desc(id)
            );
        }
    }

    public TaskDto updateAssignee(
        UUID id,
        String assigneeEmail
) {

    Task task = taskRepository.findById(id)
            .orElseThrow(() ->
                    new IllegalArgumentException(
                            "Task not found: " + id
                    )
            );

    if (assigneeEmail == null ||
            assigneeEmail.isBlank()) {

        task.setAssignee(null);
        task.setAssigneeEmail(null);

    } else {

        task.setAssigneeEmail(
                assigneeEmail
        );

        String assigneeName =
                employeeRepository
                        .findByEmailIgnoreCase(
                                assigneeEmail.trim()
                        )
                        .map(employee -> employee.getName())
                        .filter(name -> !name.isBlank())
                        .orElse(
                                assigneeEmail.trim()
                        );

        task.setAssignee(
                assigneeName
        );
    }

    return toDto(
            taskRepository.save(task)
    );
}

    public TaskDto updatePriority(
                UUID id,
                TaskPriority priority
    ) {

        Task task = taskRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Task not found: " + id
                        )
                );

        task.setPriority(priority);

        return toDto(taskRepository.save(task));
    }

    public List<TaskDto> filterTasks(
                TaskStatus status,
                TaskPriority priority,
                String assigneeEmail,
                String sourceDomain,
                EmailSourceType sourceType
    ) {

        Specification<Task> specification =
                buildTaskSpecification(
                        status,
                        priority,
                        assigneeEmail,
                        sourceDomain,
                        sourceType
                );

        return taskRepository
                .findAll(specification)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public TaskDto getBySourceEmailId(String sourceEmailId) {

        return taskRepository
                .findBySourceEmailId(sourceEmailId)
                .map(this::toDto)
                .orElse(null);
    }
    
    public List<TaskDto> getAllTasks() {

        return taskRepository.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Server-side paginated task list read path.
     *
     * List results use a lightweight DTO so full email-body/detail payloads
     * are only loaded when the user opens an individual task.
     */
    @Transactional(readOnly = true)
    public TaskPageResponse getTaskPage(
            int page,
            int size,
            TaskStatus status,
            TaskPriority priority,
            String assigneeEmail,
            String sourceDomain,
            EmailSourceType sourceType,
            String search,
            String attention,
            String sort
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);

        Specification<Task> specification = buildTaskSpecification(
                status,
                priority,
                assigneeEmail,
                sourceDomain,
                sourceType,
                search,
                attention,
                sort
        );

        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<Task> taskPage = taskRepository.findAll(specification, pageable);

        List<TaskListItemDto> items = taskPage.getContent()
                .stream()
                .map(this::toListItemDto)
                .toList();

        return new TaskPageResponse(
                items,
                taskPage.getNumber(),
                taskPage.getSize(),
                taskPage.getTotalElements(),
                taskPage.getTotalPages(),
                taskPage.hasNext(),
                taskPage.hasPrevious()
        );
    }

    @Transactional(readOnly = true)
    public TaskDashboardResponse getDashboard(
            TaskStatus status,
            TaskPriority priority,
            int days
    ) {
        int safeDays = Math.min(Math.max(days, 1), 31);
        String statusValue = status == null ? null : status.name();
        String priorityValue = priority == null ? null : priority.name();

        long actionableEmails = taskRepository.countDistinctTaskSources(
                statusValue,
                priorityValue
        );

        List<TaskAssigneeCountDto> tasksByAssignee =
                taskRepository.findTaskCountsByAssignee(
                                statusValue,
                                priorityValue
                        )
                        .stream()
                        .limit(5)
                        .map(row -> new TaskAssigneeCountDto(
                                (String) row[0],
                                ((Number) row[1]).longValue()
                        ))
                        .toList();

        // Preserve the Tasks page's previous behavior: employee workload was
        // derived from the complete task set, independent of the Dashboard's
        // status/priority filters. The aggregation is now server-side, but
        // the semantics remain unchanged.
        List<EmployeeWorkloadDto> employeeWorkload =
                taskRepository.findEmployeeWorkload(
                                null,
                                null
                        )
                        .stream()
                        .map(row -> new EmployeeWorkloadDto(
                                (String) row[0],
                                ((Number) row[1]).longValue(),
                                ((Number) row[2]).longValue(),
                                ((Number) row[3]).longValue(),
                                ((Number) row[4]).longValue()
                        ))
                        .toList();

        Specification<Task> base = buildTaskSpecification(
                status,
                priority,
                null,
                null,
                null
        );

        Specification<Task> open = base.and(
                (root, query, cb) -> cb.not(
                        root.get("status").in(
                                TaskStatus.COMPLETED,
                                TaskStatus.ARCHIVED
                        )
                )
        );

        LocalDate today = LocalDate.now();

        Specification<Task> overdue = open.and(
                (root, query, cb) ->
                        cb.lessThan(root.get("dueDate"), today)
        );

        Specification<Task> highPriority = open.and(
                (root, query, cb) -> root.get("priority").in(
                        TaskPriority.HIGH,
                        TaskPriority.CRITICAL
                )
        );

        long overdueCount = taskRepository.count(overdue);
        long highPriorityCount = taskRepository.count(highPriority);

        List<TaskDashboardTaskDto> overdueItems =
                taskRepository.findAll(
                                overdue,
                                PageRequest.of(
                                        0,
                                        6,
                                        Sort.by(
                                                Sort.Direction.ASC,
                                                "dueDate"
                                        )
                                )
                        )
                        .getContent()
                        .stream()
                        .map(this::toDto)
                        .map(task -> TaskDashboardTaskDto.fromTask(task, "overdue"))
                        .toList();

        List<TaskDashboardTaskDto> highPriorityItems =
                taskRepository.findAll(
                                highPriority,
                                PageRequest.of(
                                        0,
                                        6,
                                        Sort.by(
                                                Sort.Direction.ASC,
                                                "dueDate"
                                        )
                                )
                        )
                        .getContent()
                        .stream()
                        .map(this::toDto)
                        .map(task -> TaskDashboardTaskDto.fromTask(task, "priority"))
                        .toList();

        Map<UUID, TaskDashboardTaskDto> attentionById =
                new LinkedHashMap<>();

        overdueItems.forEach(
                item -> attentionById.put(item.id(), item)
        );

        highPriorityItems.forEach(
                item -> attentionById.putIfAbsent(item.id(), item)
        );

        List<TaskDashboardTaskDto> attentionItems =
                attentionById.values()
                        .stream()
                        .sorted(
                                Comparator
                                        .comparing(
                                                (TaskDashboardTaskDto t) ->
                                                        "overdue".equals(t._reason()) ? 0 : 1
                                        )
                                        .thenComparing(
                                                t -> t.dueDate() == null
                                                        ? LocalDate.MAX
                                                        : t.dueDate()
                                        )
                        )
                        .limit(6)
                        .toList();

        Specification<Task> upcoming = open.and(
                (root, query, cb) ->
                        cb.greaterThanOrEqualTo(
                                root.get("dueDate"),
                                today
                        )
        );

        List<TaskDashboardTaskDto> upcomingDeadlines =
                taskRepository.findAll(
                                upcoming,
                                PageRequest.of(
                                        0,
                                        5,
                                        Sort.by(
                                                Sort.Direction.ASC,
                                                "dueDate"
                                        )
                                )
                        )
                        .getContent()
                        .stream()
                        .map(this::toDto)
                        .map(TaskDashboardTaskDto::fromTask)
                        .toList();

        List<TaskDashboardTaskDto> recentActivity =
                taskRepository.findAll(
                                base,
                                PageRequest.of(
                                        0,
                                        6,
                                        Sort.by(
                                                Sort.Direction.DESC,
                                                "updatedAt"
                                        )
                                )
                        )
                        .getContent()
                        .stream()
                        .map(this::toDto)
                        .map(TaskDashboardTaskDto::fromTask)
                        .toList();

        OffsetDateTime fromTimestamp =
                LocalDate.now(ZoneOffset.UTC)
                        .minusDays(safeDays - 1L)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toOffsetDateTime();

        Map<LocalDate, Long> trendByDate =
                new LinkedHashMap<>();

        for (int i = safeDays - 1; i >= 0; i--) {
            trendByDate.put(
                    today.minusDays(i),
                    0L
            );
        }

        for (Object[] row : taskRepository.findTaskTrend(
                fromTimestamp,
                statusValue,
                priorityValue
        )) {
            LocalDate date = row[0] instanceof java.sql.Date sqlDate
                    ? sqlDate.toLocalDate()
                    : LocalDate.parse(row[0].toString());

            trendByDate.computeIfPresent(
                    date,
                    (key, value) -> ((Number) row[1]).longValue()
            );
        }

        List<TaskTrendPointDto> taskTrend =
                trendByDate.entrySet()
                        .stream()
                        .map(entry -> new TaskTrendPointDto(
                                entry.getKey(),
                                entry.getValue()
                        ))
                        .toList();

        return new TaskDashboardResponse(
                actionableEmails,
                tasksByAssignee,
                employeeWorkload,
                overdueCount,
                highPriorityCount,
                attentionItems,
                upcomingDeadlines,
                recentActivity,
                taskTrend
        );
    }

    public TaskDto getTask(UUID id) {

        Task task = taskRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Task not found: " + id
                        )
                );

        return toDto(task);
    }

    public TaskDto createTask(TaskDto dto) {

        Task task = new Task();

        task.setTitle(dto.title());
        task.setDescription(dto.description());
        task.setAssignee(dto.assignee());
        task.setAssigneeEmail(dto.assigneeEmail());
        task.setDueDate(dto.dueDate());
        task.setPriority(dto.priority());
        task.setStatus(
                dto.status() != null
                        ? dto.status()
                        : TaskStatus.NEW
        );

        task.setSourceEmailId(dto.sourceEmailId());
        task.setSourceSubject(dto.sourceSubject());
        task.setSourceSender(dto.sourceSender());
        task.setNormalizedSender(
                SenderNormalizer.normalize(dto.sourceSender())
        );
        task.setSourceMailbox(dto.sourceMailbox());
        task.setSourceDomain(dto.sourceDomain());
        task.setSourceType(dto.sourceType());
        task.setAiReason(dto.aiReason());

        return toDto(taskRepository.save(task));
    }

    public TaskDto updateStatus(
            UUID id,
            TaskStatus status
    ) {

        Task task = taskRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Task not found: " + id
                        )
                );

        task.setStatus(status);

        return toDto(taskRepository.save(task));
    }

    public List<TaskDto> getByStatus(TaskStatus status) {

        return taskRepository.findByStatus(status)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public List<TaskDto> getOverdueTasks() {

        return taskRepository
                .findByDueDateBeforeAndStatusNotIn(
                        LocalDate.now(),
                        List.of(TaskStatus.COMPLETED, TaskStatus.ARCHIVED)
                )
                .stream()
                .map(this::toDto)
                .toList();
    }

    private TaskListItemDto toListItemDto(Task task) {

        return new TaskListItemDto(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getAssignee(),
                task.getDueDate(),
                task.getPriority(),
                task.getStatus(),
                task.getSourceSubject(),
                task.getSourceSender()
        );
    }

    private TaskDto toDto(Task task) {

        return new TaskDto(
        task.getId(),
        task.getTitle(),
        task.getDescription(),
        task.getAssignee(),
        task.getAssigneeEmail(),
        task.getDueDate(),
        task.getPriority(),
        task.getStatus(),
        task.getSourceEmailId(),
        task.getSourceSubject(),
        task.getSourceSender(),
        task.getSourceMailbox(),
        task.getSourceDomain(),
        task.getSourceType(),
        task.getAiReason(),
        task.getCreatedAt(),
        task.getUpdatedAt(),
        task.getEmailReceivedAt(),
        task.getSourceEmailBody()
);
    }

    public TaskSummaryResponse getSummary(
                TaskStatus status,
                TaskPriority priority,
                String assigneeEmail,
                String sourceDomain,
                EmailSourceType sourceType
     ) {

        Specification<Task> specification =
                buildTaskSpecification(
                        status,
                        priority,
                        assigneeEmail,
                        sourceDomain,
                        sourceType
                );

        long total =
                taskRepository.count(specification);

        long newTasks =
                taskRepository.count(
                        specification.and(
                                (root, query, cb) ->
                                        cb.equal(
                                                root.get("status"),
                                                TaskStatus.NEW
                                        )
                        )
                );

        long inProgress =
                taskRepository.count(
                        specification.and(
                                (root, query, cb) ->
                                        cb.equal(
                                                root.get("status"),
                                                TaskStatus.IN_PROGRESS
                                        )
                        )
                );

        long completed =
                taskRepository.count(
                        specification.and(
                                (root, query, cb) ->
                                        cb.equal(
                                                root.get("status"),
                                                TaskStatus.COMPLETED
                                        )
                        )
                );

        long blocked =
                taskRepository.count(
                        specification.and(
                                (root, query, cb) ->
                                        cb.equal(
                                                root.get("status"),
                                                TaskStatus.BLOCKED
                                        )
                        )
                );

        long overdue =
                taskRepository.count(
                        specification.and(
                                (root, query, cb) ->
                                        cb.and(
                                                cb.lessThan(
                                                        root.get("dueDate"),
                                                        LocalDate.now()
                                                ),
                                                cb.not(
                                                        root.get("status").in(
                                                                TaskStatus.COMPLETED,
                                                                TaskStatus.ARCHIVED
                                                        )
                                                )
                                        )
                        )
                );

        long highPriority =
                taskRepository.count(
                        specification.and(
                                (root, query, cb) ->
                                        cb.equal(
                                                root.get("priority"),
                                                TaskPriority.HIGH
                                        )
                        )
                );

        return new TaskSummaryResponse(
                total,
                newTasks,
                inProgress,
                completed,
                blocked,
                overdue,
                highPriority
        );
        }

    public TaskFilterOptionsResponse getFilterOptions() {

        List<TaskFilterOptionsResponse.AssigneeOption> assignees =
                employeeRepository
                        .findByMicrosoftUserIdIsNotNullOrderByNameAsc()
                        .stream()
                        .map(employee ->
                                new TaskFilterOptionsResponse.AssigneeOption(
                                        employee.getName(),
                                        employee.getEmail()
                                )
                        )
                        .toList();

        List<String> sourceDomains =
                taskRepository.findDistinctSourceDomains();

        List<String> sourceTypes =
                taskRepository.findDistinctSourceTypes()
                        .stream()
                        .map(Enum::name)
                        .toList();

        return new TaskFilterOptionsResponse(
                assignees,
                sourceDomains,
                sourceTypes
        );
    }
}