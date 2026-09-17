package com.poc.aiassistant.service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Classifies an LLM-call failure into {@link LlmFailureCategory}
 * without ever inspecting which provider or model was involved.
 *
 * IMPORTANT: this class must never contain a provider name, an RPD/
 * RPM number, or a reset-time assumption. The one provider-adjacent
 * signal it does use — {@link #ROUTER_EXHAUSTED_MARKERS} — is
 * LiteLLM's OWN router vocabulary (its generic "I have no more
 * deployments to try" wording), not any upstream provider's. That
 * distinction matters: it is equally true whether the deployments
 * behind a model group are free-tier, paid, frontier, or self-hosted.
 */
public final class LlmFailureClassifier {

    private LlmFailureClassifier() {
    }

    /**
     * LiteLLM's router emits phrasing along these lines when it has
     * exhausted the primary model group AND every configured
     * fallback for a logical call. This is deliberately matched as
     * lowercase substrings of the exception's message chain (message
     * + cause messages + HTTP response body where available), not as
     * a specific provider's error shape.
     */
    private static final String[] ROUTER_EXHAUSTED_MARKERS = {
            "no fallback model group found",
            "no healthy deployment",
            "no deployments available",
            "all deployments unhealthy",
            "no models configured"
    };

    public static LlmFailureCategory classify(Throwable failure) {

        if (failure == null) {
            return LlmFailureCategory.TRANSIENT;
        }

        // Explicit, type-based signals from our own code take
        // precedence over any text-matching heuristic below.
        if (failure instanceof LlmTaskExtractionService.NonRetryableTaskExtractionException) {
            return LlmFailureCategory.NON_RETRYABLE;
        }
        if (failure instanceof LlmTaskExtractionService.LlmProviderUnavailableException) {
            return LlmFailureCategory.UNAVAILABLE;
        }
        if (failure instanceof TaskPersistenceService.TaskVerificationMalformedException) {
            return LlmFailureCategory.NON_RETRYABLE;
        }
        if (failure instanceof TaskPersistenceService.TaskVerificationUnavailableException) {
            return LlmFailureCategory.UNAVAILABLE;
        }

        // A 404 on an OpenAI-compatible chat-completions endpoint
        // means the requested model/route does not exist — this is a
        // structural, permanent condition (confirmed via a real
        // incident: a retired Gemini model returned exactly this),
        // not something that will ever succeed on retry or after a
        // defer window. Checked via the general HttpStatusCodeException
        // status API rather than assuming a specific named subclass
        // (e.g. HttpClientErrorException.NotFound) is present, since
        // the numeric status code is the more robust, guaranteed
        // signal. This intentionally takes precedence over the
        // router-exhaustion text scan below, since a definitive status
        // code is more reliable than a text-matching heuristic.
        if (failure instanceof HttpStatusCodeException httpFailure
                && httpFailure.getStatusCode().value() == 404) {
            return LlmFailureCategory.NON_RETRYABLE;
        }

        String messageChain = messageChain(failure).toLowerCase(Locale.ROOT);

        for (String marker : ROUTER_EXHAUSTED_MARKERS) {
            if (messageChain.contains(marker)) {
                return LlmFailureCategory.UNAVAILABLE;
            }
        }

        // Anything that reached the network but didn't get a usable
        // answer (429, other 4xx from the gateway, 5xx, connection/
        // read timeout) is treated as a short-lived blip UNLESS the
        // router-exhaustion text above already said otherwise. This
        // is intentionally coarse: distinguishing "this exact 429
        // will clear in seconds" from "this 429 means the whole
        // group is out of budget for hours" is exactly what the
        // ROUTER_EXHAUSTED_MARKERS check above already does whenever
        // LiteLLM tells us so; when it doesn't, defaulting to a
        // short retry is the safe, conservative choice.
        if (failure instanceof HttpStatusCodeException
                || failure instanceof HttpServerErrorException
                || failure instanceof ResourceAccessException
                || failure instanceof SocketTimeoutException
                || failure instanceof ConnectException) {
            return LlmFailureCategory.TRANSIENT;
        }

        Throwable cause = failure.getCause();
        if (cause != null && cause != failure) {
            return classify(cause);
        }

        return LlmFailureCategory.TRANSIENT;
    }

    /**
     * Generic Retry-After extraction: if the provider/LiteLLM told us
     * how long to wait, use that value directly instead of any local
     * policy. Returns null when no such signal exists, in which case
     * the caller must fall back to its own configured defer/backoff
     * policy — never to an assumption about a specific provider's
     * reset schedule.
     */
    public static Duration extractRetryAfter(Throwable failure) {

        if (!(failure instanceof HttpStatusCodeException httpFailure)) {
            return null;
        }

        HttpHeaders headers = httpFailure.getResponseHeaders();
        if (headers == null) {
            return null;
        }

        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter == null || retryAfter.isBlank()) {
            return null;
        }

        try {
            long seconds = Long.parseLong(retryAfter.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notASeconds) {
            // Retry-After can also be an HTTP-date; not handled here
            // since none of the LiteLLM/provider responses observed
            // so far use that form. Falls through to the caller's
            // configured defer policy, which is always a safe default.
            return null;
        }
    }

    private static String messageChain(Throwable failure) {

        StringBuilder combined = new StringBuilder();
        Throwable current = failure;
        int depth = 0;

        while (current != null && depth < 6) {

            if (current.getMessage() != null) {
                combined.append(current.getMessage()).append(' ');
            }

            if (current instanceof HttpStatusCodeException httpFailure) {
                String body = httpFailure.getResponseBodyAsString();
                if (body != null) {
                    combined.append(body).append(' ');
                }
            }

            current = current.getCause() == current ? null : current.getCause();
            depth++;
        }

        return combined.toString();
    }
}
