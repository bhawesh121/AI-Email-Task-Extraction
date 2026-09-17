package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.entity.Employee;
import com.poc.aiassistant.repository.EmployeeRepository;
import com.poc.aiassistant.service.SlaEligibilityService;

class SlaEligibilityServiceTest {

    private EmployeeRepository employeeRepository;

    @BeforeEach
    void setUp() {
        employeeRepository = mock(EmployeeRepository.class);
        when(employeeRepository.findByMicrosoftUserIdIsNotNullOrderByNameAsc())
                .thenReturn(List.of());
    }

    private EmailDto email(String senderEmail) {
        return new EmailDto(
                "m1",
                "Need a response",
                "Customer",
                senderEmail,
                senderEmail.contains("@")
                        ? senderEmail.substring(senderEmail.indexOf('@') + 1)
                        : "unknown",
                "EXTERNAL",
                "2026-09-11T08:00:00Z",
                "body",
                List.of(),
                List.of(),
                "mailbox-1",
                "conversation-1"
        );
    }

    @Test
    void responseRequiredFalse_isNotEligible() {
        SlaEligibilityService service = new SlaEligibilityService(employeeRepository, "contoso.onmicrosoft.com");

        assertFalse(service.isEligibleForResponseSla(email("customer@example.com"), false));
    }

    @Test
    void configuredTenantDomain_isNotEligible() {
        SlaEligibilityService service = new SlaEligibilityService(employeeRepository, "contoso.onmicrosoft.com");

        assertFalse(service.isEligibleForResponseSla(email("employee@contoso.onmicrosoft.com"), true));
    }

    @Test
    void employeeObservedTenantDomain_isNotEligible() {
        Employee employee = new Employee("Employee", "employee@contoso.com");
        employee.setMicrosoftUserId("graph-user-1");
        when(employeeRepository.findByMicrosoftUserIdIsNotNullOrderByNameAsc())
                .thenReturn(List.of(employee));

        SlaEligibilityService service = new SlaEligibilityService(employeeRepository, "contoso.onmicrosoft.com");

        assertFalse(service.isEligibleForResponseSla(email("customer@contoso.com"), true));
    }

    @Test
    void unknownSenderDomain_isNotEligible() {
        SlaEligibilityService service = new SlaEligibilityService(employeeRepository, "contoso.onmicrosoft.com");

        assertFalse(service.isEligibleForResponseSla(email("not-an-email-address"), true));
    }

    @Test
    void externalDomainWithResponseRequired_isEligible() {
        SlaEligibilityService service = new SlaEligibilityService(employeeRepository, "contoso.onmicrosoft.com");

        assertTrue(service.isEligibleForResponseSla(email("customer@external.com"), true));
    }
}
