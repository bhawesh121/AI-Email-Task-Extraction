package com.poc.aiassistant.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.poc.aiassistant.entity.Task;
import com.poc.aiassistant.repository.TaskRepository;
import com.poc.aiassistant.service.OneDriveExcelService;

@RestController
@RequestMapping("/api/admin/excel")
public class ExcelTestController {

    private final TaskRepository taskRepository;
    private final OneDriveExcelService oneDriveExcelService;

    public ExcelTestController(
            TaskRepository taskRepository,
            OneDriveExcelService oneDriveExcelService
    ) {
        this.taskRepository = taskRepository;
        this.oneDriveExcelService = oneDriveExcelService;
    }

    @PostMapping("/tasks/{taskId}")
    public ResponseEntity<String> exportTask(
            @PathVariable UUID taskId
    ) {

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Task not found: " + taskId
                        )
                );

        oneDriveExcelService.appendTask(task);

        return ResponseEntity.ok(
                "Task exported to OneDrive Excel successfully: "
                        + task.getId()
        );
    }
}