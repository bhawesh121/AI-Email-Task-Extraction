package com.poc.aiassistant.scheduler;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.TaskDto;
import com.poc.aiassistant.service.EmailTaskService;
import com.poc.aiassistant.service.GraphEmailService;

@Component
public class EmailInboxScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(EmailInboxScheduler.class);

    private final GraphEmailService graphEmailService;
    private final EmailTaskService emailTaskService;

    public EmailInboxScheduler(
            GraphEmailService graphEmailService,
            EmailTaskService emailTaskService
    ) {
        this.graphEmailService = graphEmailService;
        this.emailTaskService = emailTaskService;
    }

//     @Scheduled(
//             fixedDelayString = "${email.sync.fixed-delay:60000}"
//     )
    public void processInbox() {

        log.info("Starting automatic email inbox synchronization");

        try {

            List<EmailDto> emails =
                    graphEmailService.getInboxEmails();

            log.info(
                    "Fetched {} emails from Microsoft Graph",
                    emails.size()
            );

            List<TaskDto> createdTasks =
                    emailTaskService.processInbox(emails);

            log.info(
                    "Inbox synchronization completed. Created {} new tasks",
                    createdTasks.size()
            );

        } catch (Exception e) {

            log.error(
                    "Email inbox synchronization failed",
                    e
            );
        }
    }
}