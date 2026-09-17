package com.poc.aiassistant.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.entity.Employee;
import com.poc.aiassistant.repository.EmployeeRepository;

/**
 * Centralizes the decision about whether an incoming email is eligible for
 * response-SLA tracking.
 *
 * Business rule:
 *   - Internal/tenant domains are excluded from SLA.
 *   - All other sender domains are external/customer-facing candidates.
 *   - The LLM's responseRequired flag is the second and final gate.
 *
 * The existing organization.domain configuration is treated as the primary
 * tenant domain. Domains observed on synchronized Microsoft 365 employees
 * are also treated as internal so additional tenant domains do not
 * accidentally become SLA-eligible merely because they are not the primary
 * configured domain.
 */
@Service
public class SlaEligibilityService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final EmployeeRepository employeeRepository;
    private final Set<String> configuredTenantDomains;

    private volatile Set<String> cachedEmployeeDomains = Set.of();
    private volatile Instant cachedAt = Instant.EPOCH;

    public SlaEligibilityService(
            EmployeeRepository employeeRepository,
            @Value("${organization.domain:}") String organizationDomain
    ) {
        this.employeeRepository = employeeRepository;
        this.configuredTenantDomains = splitDomains(organizationDomain);
    }

    /**
     * Invalidates the cached tenant-domain snapshot. The tenant email sync
     * invokes this after it synchronizes Microsoft 365 users so newly
     * discovered tenant domains are reflected before queued emails are
     * processed.
     */
    public void refresh() {
        cachedAt = Instant.EPOCH;
    }

    public boolean isEligibleForResponseSla(
            EmailDto email,
            boolean responseRequired
    ) {
        if (!responseRequired || email == null) {
            return false;
        }

        String senderDomain = domainOf(email);

        if (senderDomain == null || "unknown".equals(senderDomain)) {
            return false;
        }

        return !isTenantDomain(senderDomain);
    }

    private boolean isTenantDomain(String domain) {
        String normalized = normalize(domain);
        if (normalized == null || "unknown".equals(normalized)) {
            return false;
        }

        if (configuredTenantDomains.contains(normalized)) {
            return true;
        }

        return employeeDomains().contains(normalized);
    }

    private Set<String> employeeDomains() {
        Instant now = Instant.now();
        if (Duration.between(cachedAt, now).compareTo(CACHE_TTL) < 0) {
            return cachedEmployeeDomains;
        }

        synchronized (this) {
            if (Duration.between(cachedAt, now).compareTo(CACHE_TTL) < 0) {
                return cachedEmployeeDomains;
            }

            cachedEmployeeDomains = employeeRepository
                    .findByMicrosoftUserIdIsNotNullOrderByNameAsc()
                    .stream()
                    .map(Employee::getEmail)
                    .map(this::domainOfAddress)
                    .filter(domain -> domain != null)
                    .collect(Collectors.toUnmodifiableSet());

            cachedAt = now;
            return cachedEmployeeDomains;
        }
    }

    private String domainOf(EmailDto email) {
        if (email.senderDomain() != null && !email.senderDomain().isBlank()) {
            return normalize(email.senderDomain());
        }
        return domainOfAddress(email.senderEmail());
    }

    private String domainOfAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }

        int at = address.lastIndexOf('@');
        if (at < 0 || at == address.length() - 1) {
            return null;
        }

        return normalize(address.substring(at + 1));
    }

    private Set<String> splitDomains(String domains) {
        if (domains == null || domains.isBlank()) {
            return Set.of();
        }

        Set<String> result = new HashSet<>();
        for (String value : domains.split(",")) {
            String normalized = normalize(value);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return Set.copyOf(result);
    }

    private String normalize(String domain) {
        if (domain == null || domain.isBlank()) {
            return null;
        }
        return domain.trim().toLowerCase(Locale.ROOT);
    }
}
