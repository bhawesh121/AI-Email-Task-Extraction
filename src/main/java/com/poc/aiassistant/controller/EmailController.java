package com.poc.aiassistant.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.poc.aiassistant.dto.DashboardSummaryDto;
import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.EmailSourceDto;
import com.poc.aiassistant.dto.MailboxDto;
import com.poc.aiassistant.service.GraphEmailService;

@RestController
@RequestMapping("/api")
public class EmailController {

    private final GraphEmailService graphEmailService;

    public EmailController(
            GraphEmailService graphEmailService
    ) {
        this.graphEmailService = graphEmailService;
    }

    /**
     * Get inbox emails.
     *
     * Existing endpoint:
     * GET /api/emails/inbox
     *
     * Also available through:
     * GET /api/graph/emails
     */
    @GetMapping({
            "/emails/inbox",
            "/graph/emails"
    })
    public List<EmailDto> getInbox() {

        return graphEmailService.getInboxEmails();
    }

    /**
     * Get dashboard-visible inbox emails. Only messages received strictly
     * after the immutable application email-sync start boundary are returned.
     *
     * Existing endpoint:
     * GET /api/emails
     */
    @GetMapping("/emails")
    public List<EmailDto> getEmails() {

        return graphEmailService.getDashboardInboxEmails();
    }

    /**
     * Get a single email by Microsoft Graph message ID.
     *
     * GET /api/graph/emails/{emailId}
     */
    @GetMapping("/graph/emails/{emailId}")
    public EmailDto getEmailById(
            @PathVariable String emailId
    ) {

        return graphEmailService.getEmailById(emailId);
    }

    /**
     * Get connected mailboxes.
     *
     * GET /api/mailboxes
     */
    @GetMapping("/mailboxes")
    public List<MailboxDto> getMailboxes() {

        return graphEmailService.getMailboxes();
    }

    /**
     * Get email sources grouped by sender domain.
     *
     * GET /api/email-sources
     */
    @GetMapping("/email-sources")
    public List<EmailSourceDto> getEmailSources() {

        return graphEmailService.getEmailSources();
    }

    /**
     * Get dashboard summary.
     *
     * GET /api/dashboard/summary
     */
    @GetMapping("/dashboard/summary")
    public DashboardSummaryDto getDashboardSummary() {

        int totalEmails =
                graphEmailService.getDashboardInboxEmails().size();

        int connectedMailboxes =
                graphEmailService.getMailboxes().size();

        return new DashboardSummaryDto(
                totalEmails,
                0,
                0,
                0,
                connectedMailboxes
        );
    }
}