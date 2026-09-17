package com.poc.aiassistant.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.EmailMessageDto;
import com.poc.aiassistant.dto.ExtractedTask;
import com.poc.aiassistant.dto.TaskExtractionResult;
import com.poc.aiassistant.entity.TaskPriority;

@Service
public class LlmTaskExtractionService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    LlmTaskExtractionService.class
            );

    private final RestClient restClient;

    private final ObjectMapper objectMapper;

    private final String model;

    private final int maxTokens;

    private final int maxBodyChars;

    private final String reasoningEffort;


    private final AssigneeResolverService assigneeResolverService;

    private final LlmRateLimiterService rateLimiterService;

    public LlmTaskExtractionService(
            @Value("${litellm.base-url}") String baseUrl,
            @Value("${litellm.api-key}") String apiKey,
            @Value("${litellm.model}") String model,
            @Value("${litellm.task-extraction.max-tokens:512}") int maxTokens,
            @Value("${litellm.task-extraction.max-body-chars:16000}") int maxBodyChars,
            @Value("${litellm.task-extraction.reasoning-effort:none}") String reasoningEffort,
            @Value("${litellm.task-extraction.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${litellm.task-extraction.read-timeout-ms:30000}") int readTimeoutMs,
            ObjectMapper objectMapper,
            AssigneeResolverService assigneeResolverService,
            LlmRateLimiterService rateLimiterService
    ) {

        this.rateLimiterService = rateLimiterService;

        this.model = model;

        this.maxTokens =
                validatePositive(
                        maxTokens,
                        "litellm.task-extraction.max-tokens"
                );

        this.maxBodyChars =
                validatePositive(
                        maxBodyChars,
                        "litellm.task-extraction.max-body-chars"
                );

        this.reasoningEffort =
                normalizeReasoningEffort(reasoningEffort);

        this.objectMapper = objectMapper;

        this.assigneeResolverService =
                assigneeResolverService;

        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(
                Duration.ofMillis(
                        validatePositive(
                                connectTimeoutMs,
                                "litellm.task-extraction.connect-timeout-ms"
                        )
                )
        );

        requestFactory.setReadTimeout(
                Duration.ofMillis(
                        validatePositive(
                                readTimeoutMs,
                                "litellm.task-extraction.read-timeout-ms"
                        )
                )
        );

        this.restClient =
                RestClient.builder()
                        .baseUrl(baseUrl)
                        .requestFactory(requestFactory)
                        .defaultHeader(
                                "Authorization",
                                "Bearer " + apiKey
                        )
                        .defaultHeader(
                                "Content-Type",
                                MediaType.APPLICATION_JSON_VALUE
                        )
                        .build();

        log.info(
                "LlmTaskExtractionService initialized"
        );

        log.info(
                "LiteLLM base URL: {}",
                baseUrl
        );

        log.info(
                "LiteLLM model: {}",
                model
        );
    }

    /**
     * Extract actionable tasks from an email.
     *
     * The LLM determines:
     * - task
     * - description
     * - due date
     * - priority
     *
     * The application determines:
     * - assignee
     * - assignee email
     *
     * Assignee information comes from the Microsoft Graph
     * recipients rather than allowing the LLM to guess it.
     */
    public List<ExtractedTask> extractTasks(
            EmailDto email
    ) {
        return extractTasksInternal(email, true).tasks();
    }

    /**
     * Queue workers reserve the LLM quota before claiming work. This method
     * performs the actual extraction without charging the limiter a second time.
     */
    public List<ExtractedTask> extractTasksWithoutRateLimit(
            EmailDto email
    ) {
        return extractTasksInternal(email, false).tasks();
    }

    /**
     * Same extraction call as {@link #extractTasks(EmailDto)}, but also
     * surfaces {@code responseRequired} — determined by the SAME LLM
     * response, not a second call — for SlaService's eligibility check.
     */
    public TaskExtractionResult extractTasksAndIntelligence(
            EmailDto email
    ) {
        return extractTasksInternal(email, true);
    }

    /** Queue-path equivalent of {@link #extractTasksAndIntelligence(EmailDto)}. */
    public TaskExtractionResult extractTasksAndIntelligenceWithoutRateLimit(
            EmailDto email
    ) {
        return extractTasksInternal(email, false);
    }

    /**
     * Adapter for the ad-hoc/manual analysis endpoint (a paste-an-
     * email-in, get-tasks-out flow, distinct from the durable queue
     * path). This exists so that endpoint shares the SAME extraction
     * pipeline — same rate limiting, same finishReason=length
     * detection, same failure classification — as the production
     * queue, rather than maintaining a second, independent LLM
     * client. Two divergent implementations of the same operation is
     * exactly the kind of split that causes resilience behavior
     * fixed in one place and silently missing in the other.
     */
    public TaskExtractionResult extractTasksResult(
            EmailMessageDto email
    ) {
        if (email == null) {
            return new TaskExtractionResult(false, List.of());
        }

        EmailDto emailDto = new EmailDto(
                email.id(),
                email.subject(),
                email.senderName(),
                email.senderEmail(),
                null,
                null,
                email.receivedDateTime() == null ? null : email.receivedDateTime().toString(),
                email.bodyPreview(),
                List.of(),
                List.of(),
                email.mailbox(),
                null
        );

        List<ExtractedTask> tasks = extractTasks(emailDto);
        return new TaskExtractionResult(!tasks.isEmpty(), tasks);
    }

    private TaskExtractionResult extractTasksInternal(
            EmailDto email,
            boolean acquireRateLimit
    ) {

        if (email == null) {

            log.warn(
                    "Cannot extract tasks because email is null"
            );

            return new TaskExtractionResult(false, List.of(), false);
        }

        if (acquireRateLimit) {
            rateLimiterService.acquireOrThrow();
        }

        String prompt =
                buildPrompt(email);

        log.info(
                "Sending email to LiteLLM for task extraction. " +
                        "Subject: {}",
                email.subject()
        );

        ChatCompletionRequest request =
                new ChatCompletionRequest(
                        model,
                        List.of(
                                new ChatMessage(
                                        "system",
                                        systemPrompt()
                                ),
                                new ChatMessage(
                                        "user",
                                        prompt
                                )
                        ),
                        0.0,
                        maxTokens,
                        reasoningEffort
                );

        Instant startedAt = Instant.now();

        ChatCompletionResponse response;

        try {

            response =
                    restClient
                            .post()
                            .uri("/chat/completions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .body(ChatCompletionResponse.class);

        } catch (Exception e) {

            long elapsedMs =
                    Duration.between(
                            startedAt,
                            Instant.now()
                    ).toMillis();

            log.error(
                    "LiteLLM task extraction request failed after {} ms. " +
                            "model={}, subject={}",
                    elapsedMs,
                    model,
                    email.subject(),
                    e
            );

            if (e instanceof ResourceAccessException) {
                // Genuine connection-level failure — no HTTP response
                // was ever received (timeout, connection refused,
                // DNS failure, etc.), as opposed to a response that
                // carries a real status code (429/404/503/...). This
                // is classified as UNAVAILABLE (see
                // LlmFailureClassifier), not the ordinary TRANSIENT
                // short-retry path, since it means the entire
                // configured model group failed to respond at all —
                // not "one provider gave us a status we can reason
                // about," but "we got nothing back."
                throw new LlmProviderUnavailableException(
                        "LiteLLM task extraction request failed: no response "
                                + "received (connection-level failure)",
                        e
                );
            }

            // Any other failure (HTTP error responses such as 429/
            // 404/503, malformed responses, etc.) keeps the existing
            // generic wrapping — LlmFailureClassifier already
            // correctly inspects these via its cause-chain unwrapping
            // (status code, response body text), so no information is
            // lost by wrapping generically here.
            throw new RuntimeException(
                    "LiteLLM task extraction request failed",
                    e
            );
        }

        long elapsedMs =
                Duration.between(
                        startedAt,
                        Instant.now()
                ).toMillis();

        log.info(
                "LiteLLM task extraction response received in {} ms. " +
                        "model={}, finishReason={}",
                elapsedMs,
                model,
                response == null
                        || response.choices() == null
                        || response.choices().isEmpty()
                        ? null
                        : response.choices().get(0).finishReason()
        );

        if (response == null
                || response.choices() == null
                || response.choices().isEmpty()
                || response.choices().get(0).message() == null) {

            throw new RuntimeException(
                    "LiteLLM returned an empty task extraction response"
            );
        }

        Choice choice =
                response.choices().get(0);

        String content =
                choice.message().content();

        if (content == null
                || content.isBlank()) {

            throw new RuntimeException(
                    "LiteLLM returned empty task extraction content"
                            + " (finishReason="
                            + choice.finishReason()
                            + ")"
            );
        }

        if ("length".equals(choice.finishReason())) {

            log.error(
                    "LiteLLM task extraction response was truncated by " +
                            "the max-tokens limit ({}). subject={}, " +
                            "contentChars={}. Increase " +
                            "litellm.task-extraction.max-tokens or reduce " +
                            "max-body-chars so the model has room to " +
                            "finish the JSON. This email will not be " +
                            "retried since retrying with the same prompt " +
                            "and token budget would truncate identically.",
                    maxTokens,
                    email.subject(),
                    content.length()
            );

            // Diagnostic only: shows what actually consumed the token
            // budget. If this starts with a <think>...</think> block
            // (or similar reasoning preamble) instead of JSON, the
            // model is spending its budget on hidden reasoning despite
            // reasoning_effort=none, and the fix is a bigger max-tokens
            // headroom (or a model/provider that honors the setting)
            // rather than assuming the email body itself is too long.
            log.error(
                    "Truncated content preview (first 500 chars) for " +
                            "subject={}: {}",
                    email.subject(),
                    content.length() > 500
                            ? content.substring(0, 500)
                            : content
            );

            throw new NonRetryableTaskExtractionException(
                    "LiteLLM task extraction response was truncated "
                            + "(finishReason=length, maxTokens="
                            + maxTokens
                            + "). The JSON is incomplete and cannot be "
                            + "parsed reliably; retrying will reproduce "
                            + "the same truncation."
            );
        }

        return parseTasks(
                content,
                email
        );
    }

    /**
     * System instructions sent to the LLM.
     */
    private String systemPrompt() {

        return """
                You are a production email task extraction engine.

                Extract only genuine actionable work from the CURRENT email,
                and separately decide whether the CURRENT email requires a
                human to send a reply back to its sender.
                Return ONLY valid JSON. No markdown or explanations.

                Output:
                {
                  "responseRequired": true | false,
                  "tasks": [
                    {
                      "task": "short action-oriented title",
                      "description": "useful task details",
                      "dueDate": "YYYY-MM-DD or null",
                      "priority": "LOW | MEDIUM | HIGH | CRITICAL"
                    }
                  ]
                }

                responseRequired rules (independent of tasks/actionable work):
                - true when the sender is waiting on a reply from us: a
                  direct question, a request for information/confirmation/
                  approval, or anything phrased as needing our reply.
                - false for FYI/notification/informational emails, automated
                  system messages, marketing, and emails that only assign
                  work without expecting a reply (e.g. "please ship this by
                  Friday" needs a task but not necessarily a reply).
                - An email can be responseRequired=true with no tasks (a
                  pure question) or have tasks with responseRequired=false
                  (pure instruction, no reply expected). Decide each
                  independently.

                Task rules:
                1. Extract every distinct actionable task.
                2. Split independent actions; merge actions that form one outcome.
                3. Ignore greetings, thanks, FYI, information, signatures,
                   disclaimers, marketing, notifications and completed work.
                4. Ignore negated/cancelled actions:
                   "do not send", "already done", "no longer required".
                5. Questions requesting work are tasks:
                   "Can you review this?" -> task.
                   Pure questions are not tasks.
                6. Focus on the current email. Ignore quoted/forwarded history
                   unless the current message explicitly requests the action.
                7. Do not invent tasks, requirements, people or dates.
                8. Use an explicit deadline when available.
                   For relative dates such as "tomorrow" or "Friday",
                   use the email received date as reference.
                   If the date is ambiguous, return null.
                9. A meeting/event date is not a task deadline unless explicitly
                   linked to the required action.
                10. Priority:
                    LOW = optional/routine
                    MEDIUM = normal
                    HIGH = urgent/important/business impact
                    CRITICAL = outage/security/emergency/severe impact
                    Default to MEDIUM when urgency is unclear.
                11. Do NOT determine or generate assignee information.
                    The application resolves assignee from Microsoft Graph.
                12. Return "tasks": [] when no genuine actionable work exists.
                    responseRequired is independent and must still be set.

                Before returning, verify that every task is actionable,
                non-duplicate, supported by the email, and has no invented data.
                """;
    }

    /**
     * Builds the user prompt from the actual email.
     */
    private String buildPrompt(
            EmailDto email
    ) {

        StringBuilder prompt =
                new StringBuilder();

        prompt.append(
                "Extract actionable tasks from the following email."
        );

        prompt.append("\n\n");

        prompt.append(
                "Subject:\n"
        );

        prompt.append(
                safe(email.subject())
        );

        prompt.append("\n\n");

        prompt.append(
                "Sender:\n"
        );

        prompt.append(
                safe(email.senderEmail())
        );

        prompt.append("\n\n");

        prompt.append(
                "Recipients:\n"
        );

        if (email.recipientEmails() != null) {

            for (String recipient :
                    email.recipientEmails()) {

                prompt.append(
                        safe(recipient)
                );

                prompt.append("\n");
            }
        }

        prompt.append("\n\n");

        prompt.append(
                "Received date/time:\n"
        );

        prompt.append(
                safe(email.receivedDateTime())
        );

        prompt.append("\n\n");

        prompt.append(
                "Email Body:\n"
        );

        String body =
                safe(email.body());

        if (body.length() > maxBodyChars) {

            log.debug(
                    "Truncating email body for LLM task extraction: " +
                            "subject={}, originalChars={}, maxChars={}",
                    email.subject(),
                    body.length(),
                    maxBodyChars
            );

            body =
                    body.substring(
                            0,
                            maxBodyChars
                    )
                    + "\n\n[Email body truncated for task extraction]";
        }

        prompt.append(body);

        return prompt.toString();
    }

    /**
     * Parses the JSON returned by the LLM.
     */
    private TaskExtractionResult parseTasks(
            String llmContent,
            EmailDto email
    ) {

        if (llmContent == null
                || llmContent.isBlank()) {

            log.warn(
                    "Cannot parse tasks because LLM content is empty"
            );

            throw new RuntimeException(
                    "LLM returned empty task extraction content"
            );
        }

        try {

            log.info(
                    "Parsing LLM task JSON..."
            );

            String json =
                    cleanJsonResponse(
                            llmContent
                    );

            log.info(
                    "Cleaned LLM JSON: {}",
                    json
            );

            JsonNode root =
                    objectMapper.readTree(json);

            boolean responseRequired =
                    root.path("responseRequired").asBoolean(false);

            JsonNode tasksNode;

            if (root.isArray()) {

                log.warn(
                        "LLM returned a direct JSON array instead " +
                                "of a tasks object"
                );

                tasksNode = root;

            } else {

                tasksNode =
                        root.path("tasks");
            }

            if (!tasksNode.isArray()) {

                throw new RuntimeException(
                        "LLM response does not contain a valid tasks array"
                );
            }

            log.info(
                    "Number of task objects returned by LLM: {}. responseRequired={}",
                    tasksNode.size(),
                    responseRequired
            );

            if (tasksNode.isEmpty()) {

                log.info(
                        "LLM explicitly returned an empty tasks array"
                );

                return new TaskExtractionResult(false, List.of(), responseRequired);
            }

            List<ExtractedTask> tasks =
                    new ArrayList<>();

            /*
             * Resolve assignee once for this email.
             *
             * The name and email are resolved together so
             * they cannot accidentally refer to different
             * recipients.
             */
            log.info(
                    "Email recipient data before assignee resolution: " +
                            "names={}, emails={}, mailbox={}",
                    email.recipientNames(),
                    email.recipientEmails(),
                    email.mailbox()
            );

            Assignee assignee =
                    resolveAssignee(email);

            log.info(
                    "Resolved assignee: name={}, email={}",
                    assignee.name(),
                    assignee.email()
            );

            for (JsonNode node :
                    tasksNode) {

                log.info(
                        "Processing extracted task node: {}",
                        node
                );

                String task =
                        textOrNull(
                                node.path("task")
                        );

                String description =
                        textOrNull(
                                node.path("description")
                        );

                LocalDate dueDate =
                        parseDate(
                                node.path("dueDate")
                        );

                TaskPriority priority =
                        parsePriority(
                                node.path("priority")
                        );

                if (task == null
                        || task.isBlank()) {

                    log.warn(
                            "Skipping task because 'task' field " +
                                    "is missing or blank"
                    );

                    continue;
                }

                tasks.add(
                        new ExtractedTask(
                                task,
                                description,
                                dueDate,
                                priority,
                                assignee.name(),
                                assignee.email()
                        )
                );
            }

            log.info(
                    "Successfully parsed {} tasks from LLM response",
                    tasks.size()
            );

            return new TaskExtractionResult(!tasks.isEmpty(), tasks, responseRequired);

        } catch (Exception e) {

            log.error(
                    "Unable to parse LiteLLM task extraction response",
                    e
            );

            throw new RuntimeException(
                    "Unable to parse LiteLLM task extraction response",
                    e
            );
        }
    }

    /**
     * Resolve the assignee from Microsoft Graph recipients.
     *
     * Resolution strategy:
     *
     * 1. Prefer the recipient email directly supplied by Graph.
     * 2. If Graph supplies only the recipient name, resolve the
     *    email through AssigneeResolverService / EmployeeRepository.
     * 3. If the email cannot be resolved, preserve the recipient name.
     */
    /**
 * Resolve the assignee from the source mailbox owner.
 *
 * Automatic tenant processing puts the Microsoft Graph user id in
 * EmailDto.mailbox(). Delegated/manual flows may provide the mailbox
 * email address, and AssigneeResolverService handles that as a
 * compatibility fallback.
 *
 * The LLM never determines the assignee and email recipients are not
 * used for task ownership.
 */
private Assignee resolveAssignee(
        EmailDto email
) {

    if (email == null
            || email.mailbox() == null
            || email.mailbox().isBlank()) {

        return new Assignee(null, null);
    }

    return assigneeResolverService
            .resolveByMailbox(email.mailbox())
            .map(employee ->
                    new Assignee(
                            employee.getName(),
                            employee.getEmail()
                    )
            )
            .orElseGet(() -> {
                log.warn(
                        "Unable to resolve source mailbox owner as an employee: mailbox={}",
                        email.mailbox()
                );

                return new Assignee(null, null);
            });
}

    /**
     * Removes markdown code fences and extracts the JSON
     * portion if the model accidentally returns additional
     * text around it.
     */
    private String cleanJsonResponse(
            String content
    ) {

        String cleaned =
                content.trim();

        if (cleaned.startsWith("```")) {

            cleaned =
                    cleaned.replaceFirst(
                            "^```(?:json)?\\s*",
                            ""
                    );

            cleaned =
                    cleaned.replaceFirst(
                            "\\s*```$",
                            ""
                    );
        }

        int firstBrace =
                cleaned.indexOf('{');

        int firstBracket =
                cleaned.indexOf('[');

        int start;

        if (firstBrace == -1) {

            start = firstBracket;

        } else if (firstBracket == -1) {

            start = firstBrace;

        } else {

            start =
                    Math.min(
                            firstBrace,
                            firstBracket
                    );
        }

        if (start > 0) {

            cleaned =
                    cleaned.substring(start);
        }

        int lastBrace =
                cleaned.lastIndexOf('}');

        int lastBracket =
                cleaned.lastIndexOf(']');

        int end =
                Math.max(
                        lastBrace,
                        lastBracket
                );

        if (end >= 0
                && end < cleaned.length() - 1) {

            cleaned =
                    cleaned.substring(
                            0,
                            end + 1
                    );
        }

        return cleaned.trim();
    }

    private String textOrNull(
            JsonNode node
    ) {

        if (node == null
                || node.isMissingNode()
                || node.isNull()) {

            return null;
        }

        String value =
                node.asText();

        if (value == null
                || value.isBlank()
                || value.equalsIgnoreCase("null")) {

            return null;
        }

        return value.trim();
    }

    private LocalDate parseDate(
            JsonNode node
    ) {

        String value =
                textOrNull(node);

        if (value == null) {

            return null;
        }

        try {

            return LocalDate.parse(
                    value
            );

        } catch (Exception e) {

            log.warn(
                    "Unable to parse due date '{}'. " +
                            "Using null.",
                    value
            );

            return null;
        }
    }

    private TaskPriority parsePriority(
            JsonNode node
    ) {

        String value =
                textOrNull(node);

        if (value == null) {

            return TaskPriority.MEDIUM;
        }

        try {

            return TaskPriority.valueOf(
                    value.toUpperCase()
            );

        } catch (Exception e) {

            log.warn(
                    "Unknown task priority '{}'. " +
                            "Defaulting to MEDIUM.",
                    value
            );

            return TaskPriority.MEDIUM;
        }
    }

    private String safe(
            String value
    ) {

        return value == null
                ? ""
                : value;
    }

    private static int validatePositive(
            int value,
            String property
    ) {

        if (value <= 0) {

            throw new IllegalArgumentException(
                    property + " must be greater than zero"
            );
        }

        return value;
    }

    private static String normalizeReasoningEffort(
            String value
    ) {

        if (value == null
                || value.isBlank()) {

            return "none";
        }

        String normalized =
                value.trim().toLowerCase();

        return switch (normalized) {

            case "max",
                 "xhigh",
                 "high",
                 "medium",
                 "low",
                 "minimal",
                 "none" -> normalized;

            default -> throw new IllegalArgumentException(
                    "Unsupported LiteLLM reasoning effort: "
                            + value
            );
        };
    }

    /**
     * Signals a failure that will reproduce identically on retry
     * (e.g. output truncated at the max-tokens limit), so the caller
     * should dead-letter the email immediately instead of burning
     * through retry attempts with the same doomed prompt/config.
     */
    public static class NonRetryableTaskExtractionException
            extends RuntimeException {

        public NonRetryableTaskExtractionException(String message) {
            super(message);
        }
    }

    /**
     * Signals that the configured LLM model group produced no HTTP
     * response at all — a connection-level failure (timeout,
     * connection refused, DNS failure) as opposed to a response that
     * carries a status code we can otherwise reason about. Classified
     * as LlmFailureCategory.UNAVAILABLE (see LlmFailureClassifier),
     * routing the whole email to the DEFERRED state rather than the
     * ordinary short-retry TRANSIENT path — a connection-level
     * failure that reached the application at all means the entire
     * configured fallback chain already failed to answer, not just
     * one provider giving an ordinary error.
     */
    public static class LlmProviderUnavailableException
            extends RuntimeException {

        public LlmProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Keeps assignee name and email together.
     */
    private record Assignee(
            String name,
            String email
    ) {
    }

    /*
     * LiteLLM / OpenAI-compatible request models.
     */
    private record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        double temperature,
        Integer max_tokens,
        String reasoning_effort
     ) {}


    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatMessage(
            String role,
            String content
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatCompletionResponse(
            List<Choice> choices
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(
            @JsonProperty("finish_reason")
            String finishReason,
            ChatMessage message
    ) {
    }
}