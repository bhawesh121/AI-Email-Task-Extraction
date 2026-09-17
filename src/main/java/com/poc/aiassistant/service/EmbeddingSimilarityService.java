package com.poc.aiassistant.service;

import java.time.Duration;
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

/**
 * Replaces the old {@code LexicalSimilarityService} (plain Jaccard
 * word-overlap) as the cheap pre-filter that decides whether a
 * candidate is worth sending to {@link TaskSemanticVerificationService}
 * at all. Word overlap missed genuine paraphrases ("confirm shipping
 * address" vs "verify delivery address" scored ~0.10 despite meaning
 * the same thing) — embeddings from a real model catch this instead.
 *
 * IMPORTANT — this does NOT share LlmRateLimiterService's budget with
 * task extraction/verification. That limiter exists specifically to
 * protect the scarce, quota-limited EXTERNAL provider budget (Gemini/
 * NVIDIA/Groq, per litellm.rate-limit's comments). The configured
 * embedding model ({@code embed-cache}, routed through LiteLLM to a
 * local Ollama instance per litellm_config.yaml) has no such quota —
 * it is local compute, not a paid external call. Gating it behind the
 * same limiter would incorrectly starve extraction/verification of
 * budget for a call that doesn't actually consume any.
 *
 * On ANY failure (HTTP error, timeout, malformed response, embedding
 * count mismatch) this throws rather than silently returning a
 * default score. The caller (TaskPersistenceService) is responsible
 * for failing OPEN — i.e. treating a failed embedding call as "worth
 * verifying" rather than "definitely different" — for the same reason
 * TaskSemanticVerificationService itself never lets an availability
 * failure masquerade as a real judgment: silently skipping
 * verification on an infrastructure hiccup would reintroduce exactly
 * the duplicate-creation risk this whole area of the app exists to
 * prevent.
 */
@Service
public class EmbeddingSimilarityService {

    private static final Logger log =
            LoggerFactory.getLogger(EmbeddingSimilarityService.class);

    private final RestClient restClient;
    private final String model;

    public EmbeddingSimilarityService(
            @Value("${litellm.base-url}") String baseUrl,
            @Value("${litellm.api-key}") String apiKey,
            @Value("${litellm.embedding.model:embed-cache}") String model,
            @Value("${litellm.embedding.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${litellm.embedding.read-timeout-ms:10000}") int readTimeoutMs
    ) {
        this.model = model;

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
     * Convenience wrapper matching the old LexicalSimilarityService's
     * scoreTasks(title, description, title, description) shape, so
     * call sites and tests needed minimal changes when swapping the
     * implementation.
     */
    public double scoreTasks(String titleA, String descriptionA, String titleB, String descriptionB) {
        return similarity(combine(titleA, descriptionA), combine(titleB, descriptionB));
    }

    /**
     * Cosine similarity between the embeddings of textA and textB, in
     * [-1.0, 1.0] (in practice, near [0.0, 1.0] for short non-negative
     * business text with this model family). Both texts are embedded
     * in a SINGLE request (batched input) rather than two separate
     * calls, to keep this a one-round-trip operation per candidate.
     *
     * Throws on any failure — see class javadoc on why the caller,
     * not this method, decides how to fail.
     */
    public double similarity(String textA, String textB) {

        EmbeddingResponse response = restClient.post()
                .uri("/embeddings")
                .body(new EmbeddingRequest(model, List.of(textA, textB)))
                .retrieve()
                .body(EmbeddingResponse.class);

        if (response == null || response.data() == null || response.data().size() != 2) {
            throw new IllegalStateException(
                    "Embedding response did not contain exactly 2 vectors (got "
                            + (response == null || response.data() == null ? 0 : response.data().size())
                            + ")"
            );
        }

        // Match by index rather than assuming array order mirrors
        // input order — the API contract only guarantees the index
        // field, not response ordering.
        List<Double> vectorA = null;
        List<Double> vectorB = null;
        for (EmbeddingDatum datum : response.data()) {
            if (datum.index() == 0) {
                vectorA = datum.embedding();
            } else if (datum.index() == 1) {
                vectorB = datum.embedding();
            }
        }

        if (vectorA == null || vectorB == null) {
            throw new IllegalStateException("Embedding response missing index 0 or 1");
        }

        return cosineSimilarity(vectorA, vectorB);
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.size() != b.size() || a.isEmpty()) {
            throw new IllegalStateException(
                    "Embedding vectors have mismatched or zero length: " + a.size() + " vs " + b.size()
            );
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String combine(String title, String description) {
        StringBuilder sb = new StringBuilder();
        if (title != null) {
            sb.append(title);
        }
        if (description != null) {
            sb.append(' ').append(description);
        }
        return sb.toString();
    }

    private record EmbeddingRequest(String model, List<String> input) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingDatum(
            @JsonProperty("index") int index,
            @JsonProperty("embedding") List<Double> embedding
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingResponse(
            @JsonProperty("data") List<EmbeddingDatum> data
    ) {
    }
}
