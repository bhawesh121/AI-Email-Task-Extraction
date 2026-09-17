package com.poc.aiassistant.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.MailboxDto;
import com.poc.aiassistant.service.GraphEmailService;

@RestController
public class GraphTestController {

    private final GraphEmailService graphEmailService;

    public GraphTestController(GraphEmailService graphEmailService) {
        this.graphEmailService = graphEmailService;
    }

    @GetMapping("/api/graph/me")
    public MailboxDto getCurrentUserFromGraph() {
        return graphEmailService.getConnectedMailbox();
    }

    @GetMapping("/api/graph/inbox")
    public List<EmailDto> getInbox() {
        return graphEmailService.getInboxEmails();
    }
}