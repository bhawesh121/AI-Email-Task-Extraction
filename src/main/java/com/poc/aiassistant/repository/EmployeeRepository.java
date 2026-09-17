package com.poc.aiassistant.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.poc.aiassistant.entity.Employee;

public interface EmployeeRepository
        extends JpaRepository<Employee, UUID> {

    Optional<Employee> findByNameIgnoreCase(String name);

    Optional<Employee> findByEmailIgnoreCase(String email);

    Optional<Employee> findByMicrosoftUserId(String microsoftUserId);

    List<Employee> findByMicrosoftUserIdIsNotNullOrderByNameAsc();
}