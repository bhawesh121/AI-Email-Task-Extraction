package com.poc.aiassistant.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.aiassistant.entity.Task;

/**
 * Structured semantic verification for one (new task, existing
 * active candidate) pair, both already known to share the same
 * mailbox + normalized sender.
 *
 * This is deliberately NOT embedding/vector similarity itself — it
 * remains a single structured-comparison LLM call per candidate,
 * using the same meaningful fields the rest of the app already
 * treats as task identity (title/action+object, description, due
 * date, priority, assignee). Embedding similarity (see
 * EmbeddingSimilarityService) is a separate, cheaper pre-filter that
 * decides WHETHER a candidate reaches this class at all; this class
 * makes the actual SAME/DIFFERENT/AMBIGUOUS call once it does.
 * Candidate-set sizes are kept small by the caller specifically so
 * this stays a small number of calls.
 *
 * Shares LlmRateLimiterService's budget with task extraction — there
 * is exactly one LiteLLM quota for this whole application, not a
 * separate one for verification. See TaskPersistenceService for the
 * feature flag that keeps this off by default until that shared
 * budget has been sized to include verification traffic.
 *
 * Any failure (HTTP error, malformed/unparseable response, empty
 * content) resolves to a non-VERDICT {@link VerificationResult.Outcome}
 * (UNAVAILABLE or MALFORMED) rather than throwing here, and rather
 * than being folded into a genuine Verdict.AMBIGUOUS. The caller
 * (TaskPersistenceService) is responsible for deciding what to do
 * with a non-answer — per the current design that means deferring
 * the task/email rather than creating a potentially-duplicate task,
 * which is the safe direction to fail in during an outage. See
 * TaskPersistenceService#trySemanticMatch.
 */
@Service
public class TaskSemanticVerificationService {

    private static final Logger log =
            LoggerFactory.getLogger(TaskSemanticVerificationService.class);

    public enum Verdict {
        SAME,
        DIFFERENT,
        AMBIGUOUS
    }

    /**
     * Distinguishes a genuine model judgment from the two ways a
     * verification call can fail to produce one. This is what lets
     * callers tell "the model looked at both tasks and genuinely
     * couldn't decide" (VERDICT, Verdict.AMBIGUOUS) apart from
     * "the model/provider layer never actually answered" (UNAVAILABLE)
     * and "the model answered but the output was unusable given our
     * current config, e.g. truncated by max-tokens or not valid JSON"
     * (MALFORMED). Conflating any of these was the root cause of a
     * real production incident: an availability failure was recorded
     * as if it were a semantic AMBIGUOUS verdict.
     */
    public enum Outcome {
        VERDICT,
        UNAVAILABLE,
        MALFORMED
    }

    public record VerificationResult(Verdict verdict, String reason, Outcome outcome) {

        /**
         * Preserved for source compatibility with existing call
         * sites/tests that construct a genuine verdict directly
         * (e.g. {@code new VerificationResult(Verdict.SAME, "...")});
         * defaults to Outcome.VERDICT, which is always correct for a
         * verdict actually produced by the model.
         */
        public VerificationResult(Verdict verdict, String reason) {
            this(verdict, reason, Outcome.VERDICT);
        }

        /**
         * The model/provider layer did not answer at all: HTTP
         * failure, connection/read timeout, empty response, or our
         * own rate limiter had no capacity. This must never be used
         * for a response the model actually produced.
         */
        public static VerificationResult unavailable(String reason) {
            return new VerificationResult(Verdict.AMBIGUOUS, reason, Outcome.UNAVAILABLE);
        }

        /**
         * The model answered, but the output cannot be used as a
         * verdict given the current configuration: truncated by
         * max-tokens (finishReason=length), not valid JSON, missing
         * the expected field, or an unrecognized result value. Unlike
         * UNAVAILABLE, retrying this exact call against the same
         * config will reproduce the same failure — it needs a
         * configuration fix (e.g. raising max-tokens), not a retry.
         */
        public static VerificationResult malformed(String reason) {
            return new VerificationResult(Verdict.AMBIGUOUS, reason, Outcome.MALFORMED);
        }
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int maxTokens;
    private final String reasoningEffort;
    private final LlmRateLimiterService rateLimiterService;

    public TaskSemanticVerificationService(
            @Value("${litellm.base-url}") String baseUrl,
            @Value("${litellm.api-key}") String apiKey,
            @Value("${litellm.model}") String model,
            @Value("${litellm.task-verification.max-tokens:1024}") int maxTokens,
            @Value("${litellm.task-verification.reasoning-effort:none}") String reasoningEffort,
            @Value("${litellm.task-verification.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${litellm.task-verification.read-timeout-ms:20000}") int readTimeoutMs,
            ObjectMapper objectMapper,
            LlmRateLimiterService rateLimiterService
    ) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.reasoningEffort = normalizeReasoningEffort(reasoningEffort);
        this.objectMapper = objectMapper;
        this.rateLimiterService = rateLimiterService;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Compare a not-yet-persisted candidate new task against an
     * existing active task. Consumes one slot from the shared
     * LlmRateLimiterService budget; if none is available, this
     * resolves to AMBIGUOUS rather than throwing, so a busy quota
     * degrades to "flag for review" instead of blocking email
     * processing entirely.
     *
     * similarityScore and similarityThreshold are passed through as
     * INFORMATIONAL context in the prompt only — the verifier is
     * explicitly told not to treat this score as authoritative
     * either way; it is a pre-filter signal (currently embedding
     * cosine similarity — see EmbeddingSimilarityService), not proof
     * of sameness or difference. similarityScore may be NaN when the
     * caller's pre-filter itself failed and it is verifying
     * unconditionally (fail-open) rather than because of a high
     * score; the prompt renders that case as "(unavailable)".
     */
    public VerificationResult verify(
            Task newTask,
            Task existingCandidate,
            double similarityScore,
            double similarityThreshold
    ) {

        if (!rateLimiterService.hasCapacity()) {
            return VerificationResult.unavailable(
                    "LLM rate limit budget exhausted; could not verify against candidate "
                            + existingCandidate.getId()
            );
        }

        try {
            rateLimiterService.acquireOrThrow();
        } catch (LlmRateLimiterService.LlmRateLimitExceededException exhausted) {
            return VerificationResult.unavailable(
                    "LLM rate limit exceeded while acquiring a verification slot"
            );
        }

        try {
            return callVerifier(newTask, existingCandidate, similarityScore, similarityThreshold);
        } catch (Exception e) {
            log.warn(
                    "Semantic verification call failed; treating as UNAVAILABLE (not a semantic "
                            + "verdict). candidateTaskId={}",
                    existingCandidate.getId(),
                    e
            );
            return VerificationResult.unavailable(
                    "Verification call failed: " + safeMessage(e)
            );
        }
    }

    private VerificationResult callVerifier(
            Task newTask,
            Task existingCandidate,
            double similarityScore,
            double similarityThreshold
    ) {

        String prompt = buildPrompt(newTask, existingCandidate, similarityScore, similarityThreshold);

        ChatCompletionRequest request = new ChatCompletionRequest(
                model,
                List.of(
                        new ChatMessage("system", systemPrompt()),
                        new ChatMessage("user", prompt)
                ),
                0.0,
                maxTokens,
                reasoningEffort

        );

        Instant startedAt = Instant.now();

        ChatCompletionResponse response = restClient
                .post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ChatCompletionResponse.class);

        long elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();

        if (response == null
                || response.choices() == null
                || response.choices().isEmpty()
                || response.choices().get(0).message() == null) {
            log.warn("Empty semantic verification response after {} ms", elapsedMs);
            return VerificationResult.unavailable("Empty verifier response");
        }

        Choice choice = response.choices().get(0);
        String content = choice.message().content();

        log.info(
                "Semantic verification response received in {} ms. finishReason={}",
                elapsedMs,
                choice.finishReason()
        );

        return interpretResponse(choice.finishReason(), content);
    }

    /**
     * Turns a raw (finishReason, content) pair from the LLM response
     * into a {@link VerificationResult}. Extracted from
     * {@link #callVerifier} specifically so the truncation/malformed
     * paths — most importantly finishReason=length — can be unit
     * tested directly with hand-built values, without needing a real
     * or mocked HTTP server. Public for the same cross-package
     * testability reason as {@link #parseVerdict}.
     *
     * A truncated or otherwise incomplete response is NEVER passed
     * through to {@link #parseVerdict} as if it were complete — it
     * returns MALFORMED immediately, so it can never be
     * misinterpreted as a genuine SAME/DIFFERENT verdict.
     */
    public VerificationResult interpretResponse(String finishReason, String content) {

        if ("length".equals(finishReason)) {
            // Deterministic given the current litellm.task-verification
            // .max-tokens config — retrying (immediately OR after a
            // defer window) will truncate identically. This is a
            // MALFORMED/config outcome, not an availability outcome:
            // it needs a config fix (raise max-tokens), not a wait.
            log.warn(
                    "Semantic verifier response was truncated by max_tokens "
                            + "(litellm.task-verification.max-tokens); this will reproduce "
                            + "on retry until that value is raised. Partial content: {}",
                    content
            );
            return VerificationResult.malformed(
                    "Verifier response truncated by max_tokens before completing"
            );
        }

        if (content == null || content.isBlank()) {
            return VerificationResult.unavailable("Blank verifier response content");
        }

        return parseVerdict(content);
    }

    /** Public (not package-private) specifically so the malformed/
     * truncated-response parsing logic can be unit tested directly
     * with hand-built JSON, without needing a real or mocked HTTP
     * server for every case. The test class lives in
     * com.poc.aiassistant, a different package from this class'
     * com.poc.aiassistant.service, so package-private visibility is
     * not sufficient — this must be public. See
     * TaskSemanticVerificationServiceTest.
     */
    public VerificationResult parseVerdict(String content) {

        String cleaned = content.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```\\s*$", "").trim();
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(cleaned);
        } catch (Exception parseFailure) {
            log.warn("Unparseable semantic verifier response: {}", cleaned);
            return VerificationResult.malformed(
                    "Unparseable verifier response"
            );
        }

        String resultText = node.path("result").asText(null);
        String reason = node.path("reason").asText(null);

        if (resultText == null) {
            return VerificationResult.malformed("Verifier response missing 'result' field");
        }

        Verdict verdict;
        try {
            verdict = Verdict.valueOf(resultText.trim().toUpperCase());
        } catch (IllegalArgumentException unknownVerdict) {
            return VerificationResult.malformed(
                    "Verifier returned unrecognized result: " + resultText
            );
        }

        return new VerificationResult(verdict, reason);
    }

    private String systemPrompt() {
        return """
                You compare two extracted email tasks from the SAME sender to decide
                whether they represent the SAME logical task, DIFFERENT tasks, or
                whether you cannot tell confidently (AMBIGUOUS).

                Treat wording, phrasing, formatting, and word order as irrelevant.
                "Send the contract to John" and "Please send John the contract" are
                the SAME task.

                Treat differences in concrete identifying details as making the
                tasks DIFFERENT, even if the wording is otherwise similar:
                  - different reference numbers (invoice/order/ticket IDs, etc.)
                  - different named recipients or objects
                  - different due dates, unless one is clearly just a restatement
                    or correction of the other with no other change

                If you are not confident either way, respond AMBIGUOUS rather than
                guessing. Do not guess SAME just because the topic is similar.

                Return ONLY valid JSON, no markdown or explanation outside the JSON:
                {
                  "result": "SAME" | "DIFFERENT" | "AMBIGUOUS",
                  "reason": "one short sentence"
                }

                Your ENTIRE response must be nothing but that JSON object — no
                preamble, no chain-of-thought, no explanation before or after it,
                not even a single sentence introducing your answer. Start your
                response with { and end it with }. Any text outside the JSON
                object risks the response being truncated before the JSON
                completes and treated as a failed verification.

                A cheap local word-overlap score and the threshold used to decide
                this pair was worth checking are included below for your context
                only. Do NOT treat a low score as evidence the tasks are
                different — genuine paraphrases often share very few words
                ("send the contract to John" vs "please forward the agreement
                to John"). Base your verdict entirely on the task fields, not
                on this score.
                """;
    }

    private String buildPrompt(
            Task newTask,
            Task existingCandidate,
            double similarityScore,
            double similarityThreshold
    ) {
        String similarityScoreDisplay = Double.isNaN(similarityScore) ? "(unavailable)" : "%.2f".formatted(similarityScore);
        return """
                New task:
                - Title: %s
                - Description: %s
                - Due date: %s
                - Priority: %s
                - Assignee: %s

                Existing task:
                - Title: %s
                - Description: %s
                - Due date: %s
                - Priority: %s
                - Assignee: %s

                (context only, not evidence) Embedding similarity score: %s, threshold used to trigger this check: %.2f
                """.formatted(
                safe(newTask.getTitle()),
                safe(newTask.getDescription()),
                safe(newTask.getDueDate() == null ? null : newTask.getDueDate().toString()),
                safe(newTask.getPriority() == null ? null : newTask.getPriority().name()),
                safe(newTask.getAssignee()),
                safe(existingCandidate.getTitle()),
                safe(existingCandidate.getDescription()),
                safe(existingCandidate.getDueDate() == null ? null : existingCandidate.getDueDate().toString()),
                safe(existingCandidate.getPriority() == null ? null : existingCandidate.getPriority().name()),
                safe(existingCandidate.getAssignee()),
                similarityScoreDisplay,
                similarityThreshold
        );
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private static String normalizeReasoningEffort(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }

        String normalized = value.trim().toLowerCase();

        return switch (normalized) {
            case "max", "xhigh", "high", "medium", "low", "minimal", "none" -> normalized;
            default -> throw new IllegalArgumentException(
                    "Unsupported LiteLLM reasoning effort: " + value
            );
        };
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private record ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            double temperature,
            @JsonProperty("max_tokens") int maxTokens,
            @JsonProperty("reasoning_effort") String reasoningEffort
    ) {
    }


    private record ChatMessage(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatCompletionResponse(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(
            @JsonProperty("finish_reason") String finishReason,
            ChatMessageBody message
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatMessageBody(String role, String content) {
    }
}