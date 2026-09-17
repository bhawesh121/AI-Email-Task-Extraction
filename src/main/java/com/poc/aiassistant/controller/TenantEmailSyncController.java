package com.poc.aiassistant.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.poc.aiassistant.service.TenantEmailSyncService;

@RestController
@RequestMapping("/api/admin/email-sync")
public class TenantEmailSyncController {

    private final TenantEmailSyncService tenantEmailSyncService;

    public TenantEmailSyncController(
            TenantEmailSyncService tenantEmailSyncService
    ) {
        this.tenantEmailSyncService =
                tenantEmailSyncService;
    }

    @PostMapping
    public TenantEmailSyncService.TenantSyncResult syncTenant() {

        return tenantEmailSyncService.syncTenant();
    }
}