package com.poc.aiassistant.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.poc.aiassistant.entity.CustomerDomain;
import com.poc.aiassistant.service.CustomerDomainService;

/**
 * Runtime CRUD for the SLA customer-domain allowlist (see
 * CustomerDomainService). Deliberately database-backed and editable
 * here rather than in application.yaml: which domains count as
 * customers for SLA purposes is operational data, not deployment
 * configuration.
 */
@RestController
@RequestMapping("/api/admin/sla/customer-domains")
public class AdminSlaController {

    private final CustomerDomainService customerDomainService;

    public AdminSlaController(CustomerDomainService customerDomainService) {
        this.customerDomainService = customerDomainService;
    }

    @GetMapping
    public List<CustomerDomain> list() {
        return customerDomainService.listAll();
    }

    @PostMapping
    public CustomerDomain add(@RequestBody Map<String, String> body) {
        return customerDomainService.addDomain(body.get("domain"));
    }

    @DeleteMapping("/{id}")
    public void deactivate(@PathVariable UUID id) {
        customerDomainService.deactivateDomain(id);
    }
}
