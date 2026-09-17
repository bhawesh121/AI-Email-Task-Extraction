package com.poc.aiassistant.service;

import org.springframework.stereotype.Service;

@Service
public class EmailProcessingScheduler {

    private final GraphEmailService graphEmailService;
    private final EmailTaskService emailTaskService;

    public EmailProcessingScheduler(
            GraphEmailService graphEmailService,
            EmailTaskService emailTaskService
    ) {
        this.graphEmailService = graphEmailService;
        this.emailTaskService = emailTaskService;
    }

    // @Scheduled(fixedDelayString = "${email.processing.interval-ms:300000}")
    public void processInbox() {

        try {

            emailTaskService.processInbox(
                    graphEmailService.getInboxEmails()
            );

        } catch (Exception e) {

            // Do not allow a Graph/API failure
            // to terminate the scheduler.
            System.err.println(
                    "Email inbox processing failed: "
                            + e.getMessage()
            );
        }
    }
}