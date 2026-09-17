package com.poc.aiassistant.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.poc.aiassistant.entity.CustomerDomain;
import com.poc.aiassistant.repository.CustomerDomainRepository;

/**
 * Runtime-editable customer-domain allowlist used for SLA
 * eligibility (see SlaService).
 *
 * The allowlist is read on every processed email, so it is cached
 * in memory with a short TTL rather than hitting Postgres per
 * email; writes (add/remove/deactivate) invalidate the cache
 * immediately so admin changes take effect without waiting out the
 * TTL.
 */
@Service
public class CustomerDomainService {

    private static final Logger log = LoggerFactory.getLogger(CustomerDomainService.class);

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final CustomerDomainRepository customerDomainRepository;

    private volatile Set<String> cachedDomains = Set.of();
    private volatile Instant cachedAt = Instant.EPOCH;

    public CustomerDomainService(CustomerDomainRepository customerDomainRepository) {
        this.customerDomainRepository = customerDomainRepository;
    }

    /** Case-insensitive check against the active allowlist. */
    public boolean isCustomerDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        return activeDomains().contains(domain.trim().toLowerCase());
    }

    public List<CustomerDomain> listAll() {
        return customerDomainRepository.findAll();
    }

    @Transactional
    public CustomerDomain addDomain(String domain) {
        String normalized = normalize(domain);

        CustomerDomain existing = customerDomainRepository.findByDomainIgnoreCase(normalized).orElse(null);
        if (existing != null) {
            existing.setActive(true);
            customerDomainRepository.save(existing);
            invalidateCache();
            return existing;
        }

        CustomerDomain created = customerDomainRepository.save(new CustomerDomain(normalized));
        invalidateCache();
        log.info("Customer domain added: {}", normalized);
        return created;
    }

    @Transactional
    public void deactivateDomain(UUID id) {
        customerDomainRepository.findById(id).ifPresent(domain -> {
            domain.setActive(false);
            customerDomainRepository.save(domain);
            invalidateCache();
            log.info("Customer domain deactivated: {}", domain.getDomain());
        });
    }

    private Set<String> activeDomains() {
        Instant now = Instant.now();
        if (Duration.between(cachedAt, now).compareTo(CACHE_TTL) < 0) {
            return cachedDomains;
        }

        synchronized (this) {
            if (Duration.between(cachedAt, now).compareTo(CACHE_TTL) < 0) {
                return cachedDomains;
            }
            cachedDomains = customerDomainRepository.findByActiveTrue().stream()
                    .map(CustomerDomain::getDomain)
                    .map(String::toLowerCase)
                    .collect(Collectors.toUnmodifiableSet());
            cachedAt = now;
            return cachedDomains;
        }
    }

    private void invalidateCache() {
        cachedAt = Instant.EPOCH;
    }

    private String normalize(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
        return domain.trim().toLowerCase();
    }
}
