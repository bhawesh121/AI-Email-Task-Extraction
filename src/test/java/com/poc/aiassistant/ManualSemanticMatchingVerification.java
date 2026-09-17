package com.poc.aiassistant;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.TaskDto;
import com.poc.aiassistant.service.EmailTaskService;

/**
 * MANUAL VERIFICATION ONLY — not part of the normal automated suite.
 *
 * This calls the REAL production method (EmailTaskService#processEmailForQueue)
 * against your REAL running Postgres, and — if
 * semantic-duplicate-detection.enabled=true and a real LITELLM_API_KEY
 * is set — a REAL LiteLLM endpoint. Unlike the manual REST endpoint
 * (/api/email-tasks/process), this constructs EmailDto directly with
 * an explicit recipientNames entry, which is the only way to actually
 * exercise recipient-scoped semantic matching: the manual REST
 * endpoint always sends empty recipient lists, so assigneeEmail can
 * never resolve there and semantic matching always silently skips.
 *
 * BEFORE RUNNING (both already done in this checkpoint — verify, don't skip):
 *   1. @Disabled has been removed and REAL_EMPLOYEE_NAME/EMAIL set to
 *      a confirmed employee from your logs (Merchant1). Still worth
 *      re-confirming the exact stored name with:
 *        SELECT name, email FROM employees WHERE email ILIKE '%merchant%';
 *   2. In application.yaml (or -D system properties), set:
 *        semantic-duplicate-detection.enabled: true
 *      and make sure litellm.base-url points at a real, reachable
 *      LiteLLM instance with a valid LITELLM_API_KEY — otherwise
 *      every verification call will hit the same 401 you saw in
 *      TaskSemanticVerificationServiceTest and everything will
 *      resolve to AMBIGUOUS (which is the CORRECT fail-safe
 *      behavior, but tells you nothing about real SAME/DIFFERENT
 *      judgment quality).
 *   3. Run: mvnw test -Dtest=ManualSemanticMatchingVerification
 *
 * WHAT TO LOOK FOR IN THE OUTPUT:
 *   - Email 1 ("Send the contract to ...") should print one NEW task.
 *   - Email 2 (paraphrased, same sender+recipient) should print ZERO
 *     new tasks if the verifier correctly returns SAME — check
 *     task_duplicate_matches for a SEMANTIC_SAME row referencing it.
 *   - Email 3 (different invoice number) should print one NEW task —
 *     confirms the verifier isn't just rubber-stamping SAME for
 *     anything similar-looking.
 *
 * This test intentionally does not assert anything: the point is to
 * observe REAL LLM behavior via console output and the database, not
 * to encode expected verdicts (which the LLM getting "right" is
 * exactly the unverified thing being checked here).
 */
@SpringBootTest
class ManualSemanticMatchingVerification {

    // Confirmed from your TenantEmailSyncService logs (EMPLOYEE SYNCHRONIZED):
    //   Name: Merchant1   Email: Merchant1@Jakmerchandising.onmicrosoft.com
    // Double-check the exact stored `name` in your employees table
    // before running — AssigneeResolverService.findByNameIgnoreCase
    // is case-insensitive but still needs an exact string match
    // otherwise (e.g. "Merchant1" vs "Merchant 1" with a space would
    // NOT resolve). Run this first to confirm:
    //   SELECT name, email FROM employees WHERE email ILIKE '%merchant%';
    private static final String REAL_EMPLOYEE_NAME = "Merchant1";
    private static final String REAL_EMPLOYEE_EMAIL = "Merchant1@Jakmerchandising.onmicrosoft.com";

    private static final String TEST_MAILBOX = "manual-verification-mailbox";
    private static final String TEST_SENDER = "alice.manualtest@example.com";

    @Autowired
    private EmailTaskService emailTaskService;

    @Test
    void observeRealSemanticMatchingBehavior() {

        EmailDto email1 = email(
                "manual-test-email-1",
                "Send the contract to " + REAL_EMPLOYEE_NAME,
                "Hi team, please send the signed contract over to " + REAL_EMPLOYEE_NAME + ". Thanks."
        );

        System.out.println("=== Email 1 (original) ===");
        List<TaskDto> result1 = emailTaskService.processEmailForQueue(email1);
        printResult(result1);

        EmailDto email2 = email(
                "manual-test-email-2",
                "RE: contract",
                "Hey, just following up — could you please forward the signed agreement to "
                        + REAL_EMPLOYEE_NAME + " when you get a chance?"
        );

        System.out.println("=== Email 2 (paraphrase — expect ALREADY_CREATED / SEMANTIC_SAME) ===");
        List<TaskDto> result2 = emailTaskService.processEmailForQueue(email2);
        printResult(result2);

        EmailDto email3 = email(
                "manual-test-email-3",
                "Invoice 999 for " + REAL_EMPLOYEE_NAME,
                "Please send invoice #999 to " + REAL_EMPLOYEE_NAME + " for review."
        );

        System.out.println("=== Email 3 (different invoice number — expect a genuinely NEW task) ===");
        List<TaskDto> result3 = emailTaskService.processEmailForQueue(email3);
        printResult(result3);

        System.out.println(
                "Now check: SELECT * FROM task_duplicate_matches ORDER BY created_at DESC LIMIT 10;"
        );
    }

    private EmailDto email(String id, String subject, String body) {
        return new EmailDto(
                id,
                subject,
                "Alice Manual Test",
                TEST_SENDER,
                "example.com",
                "EXTERNAL",
                OffsetDateTime.now().toString(),
                body,
                List.of(REAL_EMPLOYEE_NAME),
                List.of(REAL_EMPLOYEE_EMAIL),
                TEST_MAILBOX,
                null
        );
    }

    private void printResult(List<TaskDto> tasks) {
        if (tasks.isEmpty()) {
            System.out.println("  -> 0 new tasks (likely ALREADY_CREATED — check task_duplicate_matches)");
            return;
        }
        for (TaskDto task : tasks) {
            System.out.println(
                    "  -> NEW task: id=" + task.id()
                            + " title=\"" + task.title() + "\""
                            + " assigneeEmail=" + task.assigneeEmail()
            );
        }
    }
}