package com.poc.aiassistant.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.ExtractedTask;
import com.poc.aiassistant.service.GraphEmailService;
import com.poc.aiassistant.service.LlmTaskExtractionService;

@RestController
@RequestMapping("/api/test/task-extraction")
public class TaskExtractionTestController {

    private final GraphEmailService graphEmailService;
    private final LlmTaskExtractionService llmTaskExtractionService;

    public TaskExtractionTestController(
            GraphEmailService graphEmailService,
            LlmTaskExtractionService llmTaskExtractionService
    ) {
        this.graphEmailService = graphEmailService;
        this.llmTaskExtractionService = llmTaskExtractionService;
    }

    @GetMapping
    public List<ExtractedTask> extractTasks(
            @RequestParam String emailId
    ) {

        EmailDto email =
                graphEmailService.getEmailById(emailId);

        return llmTaskExtractionService
                .extractTasks(email);
    }
}