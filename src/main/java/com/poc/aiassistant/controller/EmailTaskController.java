package com.poc.aiassistant.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.EmailMessageDto;
import com.poc.aiassistant.dto.TaskDto;
import com.poc.aiassistant.service.EmailTaskService;
import com.poc.aiassistant.service.GraphEmailService;

@RestController
@RequestMapping("/api/email-tasks")
public class EmailTaskController {

    private final EmailTaskService emailTaskService;
    private final GraphEmailService graphEmailService;

    public EmailTaskController(
            EmailTaskService emailTaskService,
            GraphEmailService graphEmailService
    ) {
        this.emailTaskService = emailTaskService;
        this.graphEmailService = graphEmailService;
    }

    @PostMapping("/process")
    public ResponseEntity<List<TaskDto>> processEmail(
            @RequestBody EmailMessageDto email
    ) {

        System.out.println("========== PROCESS EMAIL START ==========");

        List<TaskDto> tasks =
                emailTaskService.processEmail(email);

        System.out.println(
                "========== PROCESS EMAIL END | TASKS = "
                        + tasks.size()
                        + " =========="
        );

        return ResponseEntity.ok(tasks);
    }

    @PostMapping("/process-inbox")
    public List<TaskDto> processInbox() {

        System.out.println("========== PROCESS INBOX START ==========");

        System.out.println("Fetching inbox emails...");

        List<EmailDto> emails =
                graphEmailService.getInboxEmails();

        System.out.println(
                "Inbox emails fetched: "
                        + emails.size()
        );

        System.out.println("Starting task extraction...");

        List<TaskDto> tasks =
                emailTaskService.processInbox(emails);

        System.out.println(
                "Task extraction completed. Tasks created: "
                        + tasks.size()
        );

        System.out.println("========== PROCESS INBOX END ==========");

        return tasks;
    }
}