package com.poc.aiassistant.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.poc.aiassistant.dto.BreachedEmailDto;
import com.poc.aiassistant.dto.SlaSummaryDto;
import com.poc.aiassistant.dto.SlaTrendPointDto;
import com.poc.aiassistant.service.SlaService;

/**
 * Email Responsiveness / SLA reporting. Deliberately a small, coherent
 * surface (summary + trend + breached-email list) rather than one
 * endpoint per possible slice — the dashboard's "Email SLA &
 * Responsiveness" card and its "View Breached Emails" action are the
 * only current consumers.
 */
@RestController
@RequestMapping("/api/sla")
public class SlaController {

    private final SlaService slaService;

    public SlaController(SlaService slaService) {
        this.slaService = slaService;
    }

    @GetMapping("/summary")
    public SlaSummaryDto getSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return slaService.getSummary(date == null ? LocalDate.now() : date);
    }

    @GetMapping("/trend")
    public List<SlaTrendPointDto> getTrend(
            @RequestParam(defaultValue = "7") int days
    ) {
        int clamped = Math.max(1, Math.min(days, 90));
        return slaService.getTrend(clamped);
    }

    @GetMapping("/breached")
    public List<BreachedEmailDto> getBreached(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String mailboxUserId
    ) {
        return slaService.getBreachedEmails(from, to, mailboxUserId);
    }
}
