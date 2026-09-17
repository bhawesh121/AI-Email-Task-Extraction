package com.poc.aiassistant.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.poc.aiassistant.entity.Employee;
import com.poc.aiassistant.repository.EmployeeRepository;

@Service
public class AssigneeResolverService {

    private final EmployeeRepository employeeRepository;

    public AssigneeResolverService(
            EmployeeRepository employeeRepository
    ) {
        this.employeeRepository = employeeRepository;
    }

    /**
     * Resolve the employee who owns the source mailbox.
     *
     * Tenant mailbox processing stores the Microsoft Graph user id in
     * EmailDto.mailbox(). Interactive/delegated flows may instead carry
     * the mailbox email address, so email remains a safe compatibility
     * fallback.
     */
    public Optional<Employee> resolveByMailbox(String mailbox) {

        if (mailbox == null || mailbox.isBlank()) {
            return Optional.empty();
        }

        String value = mailbox.trim();

        return employeeRepository
                .findByMicrosoftUserId(value)
                .or(() -> employeeRepository.findByEmailIgnoreCase(value));
    }

    public String resolveEmail(String assigneeName) {

        if (assigneeName == null || assigneeName.isBlank()) {
            return null;
        }

        return employeeRepository
                .findByNameIgnoreCase(assigneeName.trim())
                .map(Employee::getEmail)
                .orElse(null);
    }
}