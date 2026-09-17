package com.poc.aiassistant.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.poc.aiassistant.entity.CustomerDomain;

public interface CustomerDomainRepository extends JpaRepository<CustomerDomain, UUID> {

    List<CustomerDomain> findByActiveTrue();

    Optional<CustomerDomain> findByDomainIgnoreCase(String domain);
}
