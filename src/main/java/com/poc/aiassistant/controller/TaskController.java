package com.poc.aiassistant.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.poc.aiassistant.dto.TaskDashboardResponse;
import com.poc.aiassistant.dto.TaskDto;
import com.poc.aiassistant.dto.TaskFilterOptionsResponse;
import com.poc.aiassistant.dto.TaskPageResponse;
import com.poc.aiassistant.dto.TaskSummaryResponse;
import com.poc.aiassistant.entity.EmailSourceType;
import com.poc.aiassistant.entity.TaskPriority;
import com.poc.aiassistant.entity.TaskStatus;
import com.poc.aiassistant.service.TaskService;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/filter-options")
    public TaskFilterOptionsResponse getFilterOptions() {
        return taskService.getFilterOptions();
    }

    @GetMapping
    public List<TaskDto> getAllTasks() {
        return taskService.getAllTasks();
    }

    /**
     * Paginated task read model for the Tasks & Workload page.
     *
     * This is additive: the existing GET /api/tasks endpoint remains
     * unchanged for the dashboard and other current consumers.
     */
    @GetMapping("/page")
    public TaskPageResponse getTaskPage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskPriority priority,
            @RequestParam(required = false) String assigneeEmail,
            @RequestParam(required = false) String sourceDomain,
            @RequestParam(required = false) EmailSourceType sourceType,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String attention,
            @RequestParam(defaultValue = "received-desc") String sort
    ) {
        return taskService.getTaskPage(
                page,
                size,
                status,
                priority,
                assigneeEmail,
                sourceDomain,
                sourceType,
                search,
                attention,
                sort
        );
    }

   @GetMapping("/filter")
    public List<TaskDto> filterTasks(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskPriority priority,
            @RequestParam(required = false) String assigneeEmail,
            @RequestParam(required = false) String sourceDomain,
            @RequestParam(required = false) EmailSourceType sourceType
    ) {
        return taskService.filterTasks(
                status,
                priority,
                assigneeEmail,
                sourceDomain,
                sourceType
        );
    }
    
    @GetMapping("/dashboard")
    public TaskDashboardResponse getDashboard(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskPriority priority,
            @RequestParam(defaultValue = "7") int days
    ) {
        return taskService.getDashboard(
                status,
                priority,
                days
        );
    }

    @GetMapping("/summary")
    public TaskSummaryResponse getSummary(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskPriority priority,
            @RequestParam(required = false) String assigneeEmail,
            @RequestParam(required = false) String sourceDomain,
            @RequestParam(required = false) EmailSourceType sourceType
    ) {
        return taskService.getSummary(
                status,
                priority,
                assigneeEmail,
                sourceDomain,
                sourceType
        );
    }

    @GetMapping("/{id:[0-9a-fA-F-]{36}}")
    public TaskDto getTask(
            @PathVariable UUID id
    ) {
        return taskService.getTask(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDto createTask(
            @RequestBody TaskDto task
    ) {
        return taskService.createTask(task);
    }

    @PatchMapping("/{id:[0-9a-fA-F-]{36}}/status")
    public TaskDto updateStatus(
            @PathVariable UUID id,
            @RequestParam TaskStatus status
    ) {
        return taskService.updateStatus(id, status);
    }

    @GetMapping("/status/{status}")
    public List<TaskDto> getByStatus(
            @PathVariable TaskStatus status
    ) {
        return taskService.getByStatus(status);
    }

    @GetMapping("/overdue")
    public List<TaskDto> getOverdueTasks() {
        return taskService.getOverdueTasks();
    }

    @PatchMapping("/{id:[0-9a-fA-F-]{36}}/priority")
    public TaskDto updatePriority(
            @PathVariable UUID id,
            @RequestParam TaskPriority priority
    ) {
        return taskService.updatePriority(id, priority);
    }

    @PatchMapping("/{id:[0-9a-fA-F-]{36}}/assignee")
    public TaskDto updateAssignee(
            @PathVariable UUID id,
            @RequestParam String assigneeEmail
    ) {
        return taskService.updateAssignee(id, assigneeEmail);
    }

}