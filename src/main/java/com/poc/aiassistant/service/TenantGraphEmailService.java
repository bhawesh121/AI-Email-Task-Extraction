package com.poc.aiassistant.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.aiassistant.dto.EmailDto;

@Service
public class TenantGraphEmailService {

    // receivedDateTime is used for both Inbox and Sent Items messages:
    // Graph populates it for Sent Items too (effectively the send
    // time), so a single timestamp field covers both folders without
    // adding a parallel sentDateTime field through EmailDto.
    private static final String MESSAGE_SELECT =
            "id,subject,from,toRecipients,ccRecipients,receivedDateTime,body,bodyPreview,conversationId";

    private final RestClient restClient;
    private final GraphApplicationTokenService tokenService;
    private final ObjectMapper objectMapper;

    public TenantGraphEmailService(
            @Value("${microsoft.graph.base-url}") String graphBaseUrl,
            GraphApplicationTokenService tokenService,
            ObjectMapper objectMapper
    ) {
        this.restClient = RestClient.builder().baseUrl(graphBaseUrl).build();
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    /**
     * Get every tenant user. The Graph collection is paged; every nextLink is
     * followed so mailbox discovery is not silently truncated at $top=100.
     */
    public List<TenantUser> getTenantUsers() {
        String url = "/users?$select=id,displayName,mail,userPrincipalName&$top=100";
        List<TenantUser> users = new ArrayList<>();

        while (url != null) {
            String response = getUrl(url);
            JsonNode root = readTree(response, "Unable to parse Microsoft Graph users response");
            JsonNode values = root.path("value");
            
            if (values.isArray()) {
                for (JsonNode user : values) {
                    String id = text(user, "id");
                    if (id != null && !id.isBlank()) {
                        users.add(new TenantUser(
                                id,
                                text(user, "displayName"),
                                text(user, "mail"),
                                text(user, "userPrincipalName")
                        ));
                    }
                }
            }
            url = text(root, "@odata.nextLink");
        }
        return users;
    }

    /**
     * Execute one page of message delta synchronization.
     * The supplied URL must be either the initial delta endpoint or the exact
     * opaque @odata.nextLink returned by Microsoft Graph.
     */
    public DeltaPage getInboxDeltaPage(
            String userId,
            String deltaLink,
            Instant initialBoundary
    ) {
        if (userId == null || userId.isBlank()) {
            return new DeltaPage(List.of(), null, null);
        }

        String url;
        if (deltaLink != null && !deltaLink.isBlank()) {
            url = deltaLink;
        } else {
            // The initial delta deliberately walks the complete folder and applies the
            // persisted application boundary locally. Graph documents a 5,000-message
            // ceiling when receivedDateTime filtering is used on a message delta query.
            url = "/users/" + userId
                    + "/mailFolders/inbox/messages/delta"
                    + "?$select=id,receivedDateTime"
                    + "&$top=50";
        }

        try {
            String response = getUrl(url);
            JsonNode root = readTree(response, "Unable to parse Microsoft Graph delta response");
            List<DeltaMessage> messages = new ArrayList<>();
            JsonNode values = root.path("value");

            if (values.isArray()) {
                for (JsonNode message : values) {
                    String messageId = text(message, "id");
                    if (messageId == null || messageId.isBlank()) {
                        continue;
                    }

                    JsonNode removed = message.path("@removed");
                    boolean removedEntry = removed != null && !removed.isMissingNode() && !removed.isNull();
                    String receivedDateTime = text(message, "receivedDateTime");
                    messages.add(new DeltaMessage(messageId, receivedDateTime, removedEntry));
                }
            }

            return new DeltaPage(
                    messages,
                    text(root, "@odata.nextLink"),
                    text(root, "@odata.deltaLink")
            );
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 410) {
                throw new DeltaTokenExpiredException(userId, ex);
            }
            throw ex;
        }
    }

    /**
     * Sent Items counterpart of {@link #getInboxDeltaPage}. Same delta
     * mechanics (opaque nextLink/deltaLink, 410 => token expired), against
     * the Sent Items folder instead of Inbox. Used by the SLA reply-
     * detection sync (see TenantSentItemsSyncService) — Sent Items is the
     * source of truth for whether/when a customer email was answered.
     */
    public DeltaPage getSentItemsDeltaPage(
            String userId,
            String deltaLink,
            Instant initialBoundary
    ) {
        if (userId == null || userId.isBlank()) {
            return new DeltaPage(List.of(), null, null);
        }

        String url;
        if (deltaLink != null && !deltaLink.isBlank()) {
            url = deltaLink;
        } else {
            url = "/users/" + userId
                    + "/mailFolders/sentItems/messages/delta"
                    + "?$select=id,receivedDateTime"
                    + "&$top=50";
        }

        try {
            String response = getUrl(url);
            JsonNode root = readTree(response, "Unable to parse Microsoft Graph Sent Items delta response");
            List<DeltaMessage> messages = new ArrayList<>();
            JsonNode values = root.path("value");

            if (values.isArray()) {
                for (JsonNode message : values) {
                    String messageId = text(message, "id");
                    if (messageId == null || messageId.isBlank()) {
                        continue;
                    }

                    JsonNode removed = message.path("@removed");
                    boolean removedEntry = removed != null && !removed.isMissingNode() && !removed.isNull();
                    String receivedDateTime = text(message, "receivedDateTime");
                    messages.add(new DeltaMessage(messageId, receivedDateTime, removedEntry));
                }
            }

            return new DeltaPage(
                    messages,
                    text(root, "@odata.nextLink"),
                    text(root, "@odata.deltaLink")
            );
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 410) {
                throw new DeltaTokenExpiredException(userId, ex);
            }
            throw ex;
        }
    }

    /**
     * Fetch the full message only after a queue worker has claimed it.
     */
    public EmailDto getMessage(String userId, String messageId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }

        try {
            String response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/users/{userId}/messages/{messageId}")
                            .queryParam("$select", MESSAGE_SELECT)
                            .build(userId, messageId))
                    .headers(headers -> headers.setBearerAuth(tokenService.getAccessToken()))
                    .retrieve()
                    .body(String.class);

            return toEmailDto(
                    readTree(response, "Unable to parse Microsoft Graph message response"),
                    userId
            );
        } catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            if (status.value() == 404) {
                throw new MessageNoLongerAvailableException(userId, messageId, ex);
            }
            throw ex;
        }
    }

    /** Existing legacy API retained for backward compatibility/manual callers. */
    public List<EmailDto> getInboxMessages(String userId) {
        return getInboxMessages(userId, null);
    }

    /** Existing legacy API retained for backward compatibility/manual callers. */
    public List<EmailDto> getInboxMessages(String userId, Instant receivedAfter) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }

        String response = restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .path("/users/{userId}/mailFolders/inbox/messages")
                            .queryParam("$select", MESSAGE_SELECT)
                            .queryParam("$orderby", "receivedDateTime desc")
                            .queryParam("$top", 50);
                    if (receivedAfter != null) {
                        builder.queryParam("$filter", "receivedDateTime gt " + receivedAfter);
                    }
                    return builder.build(userId);
                })
                .headers(headers -> headers.setBearerAuth(tokenService.getAccessToken()))
                .retrieve()
                .body(String.class);

        return parseEmails(response, userId);
    }

    private String getUrl(String url) {
        return restClient.get()
                .uri(url)
                .headers(headers -> headers.setBearerAuth(tokenService.getAccessToken()))
                .retrieve()
                .body(String.class);
    }

    private JsonNode readTree(String response, String message) {
        try {
            return objectMapper.readTree(response);
        } catch (Exception ex) {
            throw new RuntimeException(message, ex);
        }
    }

    private List<EmailDto> parseEmails(String response, String mailboxUserId) {
        JsonNode values = readTree(response, "Unable to parse Microsoft Graph mailbox response").path("value");
        if (!values.isArray()) {
            return List.of();
        }

        List<EmailDto> emails = new ArrayList<>();
        for (JsonNode message : values) {
            EmailDto email = toEmailDto(message, mailboxUserId);
            if (email != null) {
                emails.add(email);
            }
        }
        return emails;
    }

    private EmailDto toEmailDto(JsonNode message, String mailboxUserId) {
        String id = text(message, "id");
        if (id == null || id.isBlank()) {
            return null;
        }

        String subject = text(message, "subject");
        String receivedDateTime = text(message, "receivedDateTime");
        String bodyPreview = text(message, "bodyPreview");
        String body = text(message.path("body"), "content");
        String conversationId = text(message, "conversationId");

        JsonNode sender = message.path("from").path("emailAddress");
        String senderName = text(sender, "name");
        String senderEmail = text(sender, "address");

        List<String> recipientNames = new ArrayList<>();
        List<String> recipientEmails = new ArrayList<>();
        extractRecipients(message.path("toRecipients"), recipientNames, recipientEmails);
        extractRecipients(message.path("ccRecipients"), recipientNames, recipientEmails);

        return new EmailDto(
                id,
                subject,
                senderName,
                senderEmail,
                extractDomain(senderEmail),
                "INTERNAL",
                receivedDateTime,
                body != null && !body.isBlank() ? body : bodyPreview,
                recipientNames,
                recipientEmails,
                mailboxUserId,
                conversationId
        );
    }

    private void extractRecipients(
            JsonNode recipientsNode,
            List<String> recipientNames,
            List<String> recipientEmails
    ) {
        if (recipientsNode == null || !recipientsNode.isArray()) {
            return;
        }
        for (JsonNode recipient : recipientsNode) {
            JsonNode emailAddress = recipient.path("emailAddress");
            recipientNames.add(text(emailAddress, "name"));
            recipientEmails.add(text(emailAddress, "address"));
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String result = value.asText();
        return result == null || result.isBlank() ? null : result;
    }

    private String extractDomain(String email) {
        if (email == null || email.isBlank()) {
            return "unknown";
        }
        int atIndex = email.lastIndexOf('@');
        if (atIndex < 0 || atIndex == email.length() - 1) {
            return "unknown";
        }
        return email.substring(atIndex + 1).toLowerCase();
    }

    public record TenantUser(
            String id,
            String displayName,
            String mail,
            String userPrincipalName
    ) {
    }

    public record DeltaMessage(
            String messageId,
            String receivedDateTime,
            boolean removed
    ) {
    }

    public record DeltaPage(
            List<DeltaMessage> messages,
            String nextLink,
            String deltaLink
    ) {
    }

    public static class DeltaTokenExpiredException extends RuntimeException {
        public DeltaTokenExpiredException(String userId, Throwable cause) {
            super("Microsoft Graph delta token expired for mailbox " + userId, cause);
        }
    }

    public static class MessageNoLongerAvailableException extends RuntimeException {
        public MessageNoLongerAvailableException(String userId, String messageId, Throwable cause) {
            super("Microsoft Graph message is no longer available: user=" + userId + ", messageId=" + messageId, cause);
        }
    }
}
