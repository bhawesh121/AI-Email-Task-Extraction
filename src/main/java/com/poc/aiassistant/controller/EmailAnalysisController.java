package com.poc.aiassistant.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.poc.aiassistant.dto.EmailMessageDto;
import com.poc.aiassistant.dto.TaskExtractionResult;
import com.poc.aiassistant.service.LlmTaskExtractionService;

/**
 * Ad-hoc/manual "paste an email in, see the extracted tasks" analysis
 * endpoint. Deliberately routed through the SAME LlmTaskExtractionService
 * the production queue uses (see LlmTaskExtractionService#extractTasksResult)
 * rather than a separate LLM client, so resilience/classification behavior
 * cannot silently diverge between this endpoint and the queue.
 */
@RestController
@RequestMapping("/api/emails")
public class EmailAnalysisController {

    private final LlmTaskExtractionService llmTaskExtractionService;

    public EmailAnalysisController(
            LlmTaskExtractionService llmTaskExtractionService
    ) {
        this.llmTaskExtractionService = llmTaskExtractionService;
    }

    @PostMapping("/analyze")
    public TaskExtractionResult analyze(
            @RequestBody EmailMessageDto email
    ) {
        return llmTaskExtractionService.extractTasksResult(email);
    }
}