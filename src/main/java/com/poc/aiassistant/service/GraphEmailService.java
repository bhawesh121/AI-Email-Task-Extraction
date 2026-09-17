package com.poc.aiassistant.service;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.aiassistant.dto.EmailDto;
import com.poc.aiassistant.dto.EmailSourceDto;
import com.poc.aiassistant.dto.MailboxDto;
import com.poc.aiassistant.exception.AuthenticationRequiredException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class GraphEmailService {

    @Value("${organization.domain}")
    private String organizationDomain;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final ApplicationRuntime applicationRuntime;

    private final HttpServletRequest request;
    private final HttpServletResponse response;

    @Autowired
    public GraphEmailService(
            @Value("${microsoft.graph.base-url}") String baseUrl,
            OAuth2AuthorizedClientManager authorizedClientManager,
            ApplicationRuntime applicationRuntime,
            HttpServletRequest request,
            HttpServletResponse response
    ) {

        this.authorizedClientManager = authorizedClientManager;
        this.applicationRuntime = applicationRuntime;
        this.request = request;
        this.response = response;

        this.objectMapper = new ObjectMapper();

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Test-only constructor that keeps the Graph client injectable so the
     * boundary/pagination behavior can be verified without a real Graph call.
     */
    GraphEmailService(
            RestClient restClient,
            OAuth2AuthorizedClientManager authorizedClientManager,
            ApplicationRuntime applicationRuntime,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        this.restClient = restClient;
        this.authorizedClientManager = authorizedClientManager;
        this.applicationRuntime = applicationRuntime;
        this.request = request;
        this.response = response;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Get the Microsoft Graph access token for the currently
     * authenticated Microsoft user.
     *
     * The token is obtained from Spring Security's OAuth2
     * authorized client instead of being stored in application.yaml
     * or .env.
     */
    private String getAccessToken() {

        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()) {

                throw new AuthenticationRequiredException(
                        "No authenticated Microsoft user found."
                );
        }

        OAuth2AuthorizeRequest authorizeRequest =
                OAuth2AuthorizeRequest
                        .withClientRegistrationId("microsoft")
                        .principal(authentication)
                        .attribute(
                                HttpServletRequest.class.getName(),
                                request
                        )
                        .attribute(
                                HttpServletResponse.class.getName(),
                                response
                        )
                        .build();

        OAuth2AuthorizedClient authorizedClient =
                authorizedClientManager.authorize(
                        authorizeRequest
                );

        if (authorizedClient == null) {

                throw new IllegalStateException(
                        "Unable to obtain Microsoft OAuth2 authorized client."
                );
        }

        if (authorizedClient.getAccessToken() == null) {

                throw new IllegalStateException(
                        "Microsoft OAuth2 access token is unavailable."
                );
        }

        return authorizedClient
                .getAccessToken()
                .getTokenValue();
        }

    /**
     * Get emails from the signed-in user's inbox.
     *
     * We retrieve:
     *
     * - id
     * - subject
     * - sender
     * - toRecipients
     * - receivedDateTime
     * - body
     *
     * instead of bodyPreview because the AI task extractor
     * needs the complete email content.
     */
    public List<EmailDto> getInboxEmails() {

        String accessToken = getAccessToken();
        String mailbox = getConnectedMailbox(accessToken).email();
        String graphResponse = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/me/mailFolders/inbox/messages")
                        .queryParam("$top", 50)
                        .queryParam("$orderby", "receivedDateTime desc")
                        .queryParam("$select", "id,subject,from,toRecipients,receivedDateTime,body")
                        .build())
                .header(
                        "Authorization",
                        "Bearer " + accessToken
                )
                .retrieve()
                .body(String.class);

        return parseEmails(graphResponse, mailbox);
    }

    /**
     * Get only the inbox emails that fall inside the immutable application
     * email-processing window. The boundary is created once by
     * {@link ApplicationRuntime} and survives ordinary application restarts.
     *
     * Graph applies the boundary server-side so old mailbox history is not
     * transferred to the application. We still validate receivedDateTime
     * locally as defense in depth because Graph responses are an external
     * dependency and delta/query APIs can contain metadata or malformed data
     * that should never leak into the dashboard's post-startup dataset.
     */
    public List<EmailDto> getDashboardInboxEmails() {

        Instant boundary = applicationRuntime.startedAt();
        String accessToken = getAccessToken();
        String mailbox = getConnectedMailbox(accessToken).email();
        List<EmailDto> emails = new ArrayList<>();

        // The first page is built relative to the Graph base URL (so it
        // correctly resolves against https://graph.microsoft.com/v1.0).
        // Every subsequent page uses Graph's own @odata.nextLink, which is
        // already a fully-qualified absolute URL and must be used as-is.
        String nextUrl = null;
        boolean firstPage = true;

        while (firstPage || (nextUrl != null && !nextUrl.isBlank())) {

            String graphResponse;

            if (firstPage) {
                graphResponse = restClient
                        .get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/me/mailFolders/inbox/messages")
                                .queryParam("$top", 50)
                                .queryParam("$orderby", "receivedDateTime desc")
                                .queryParam("$filter", "receivedDateTime gt " + boundary)
                                .queryParam("$select", "id,subject,from,toRecipients,receivedDateTime,body")
                                .build())
                        .header(
                                "Authorization",
                                "Bearer " + accessToken
                        )
                        .retrieve()
                        .body(String.class);
                firstPage = false;
            } else {
                graphResponse = restClient
                        .get()
                        .uri(URI.create(nextUrl))
                        .header(
                                "Authorization",
                                "Bearer " + getAccessToken()
                        )
                        .retrieve()
                        .body(String.class);
            }

            JsonNode root = readTree(
                    graphResponse,
                    "Unable to parse Microsoft Graph dashboard email response"
            );

            emails.addAll(parseEmails(
                    root,
                    mailbox,
                    boundary
            ));

            JsonNode nextLink = root.path("@odata.nextLink");
            nextUrl = nextLink.isTextual()
                    ? nextLink.asText()
                    : null;
        }

        return emails;
    }

    /**
     * Get information about the connected mailbox.
     */
    public MailboxDto getConnectedMailbox() {
        return getConnectedMailbox(getAccessToken());
    }

    private MailboxDto getConnectedMailbox(String accessToken) {

        String response = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/me")
                        .queryParam("$select", "id,displayName,userPrincipalName,mail")
                        .build())
                .header(
                        "Authorization",
                        "Bearer " + accessToken
                )
                .retrieve()
                .body(String.class);

        try {

            JsonNode root =
                    objectMapper.readTree(response);

            String id =
                    root.path("id").asText();

            String displayName =
                    root.path("displayName").asText();

            String email =
                    root.path("mail").asText();

            /*
             * Some Microsoft accounts don't have the
             * "mail" property populated.
             *
             * In that case use userPrincipalName.
             */
            if (email == null || email.isBlank()) {

                email =
                        root.path("userPrincipalName")
                                .asText();
            }

            return new MailboxDto(
                    id,
                    displayName,
                    email
            );

        } catch (AuthenticationRequiredException e) {

        throw e;

        } catch (Exception e) {

            throw new RuntimeException(
                    "Unable to read mailbox information from Microsoft Graph",
                    e
            );
        }
    }

    /**
     * Return a list containing the currently connected mailbox.
     *
     * Later this can become a real multi-mailbox implementation.
     */
    public List<MailboxDto> getMailboxes() {

        return List.of(
                getConnectedMailbox()
        );
    }

    /**
     * Parse Microsoft Graph email response.
     */
    private List<EmailDto> parseEmails(
            String response,
            String mailbox
    ) {
        return parseEmails(
                readTree(response, "Unable to parse Microsoft Graph email response"),
                mailbox,
                null
        );
    }

    private List<EmailDto> parseEmails(
            JsonNode root,
            String mailbox,
            Instant receivedAfter
    ) {

        List<EmailDto> emails = new ArrayList<>();
        JsonNode values = root.path("value");

        if (!values.isArray()) {
            return emails;
        }

        for (JsonNode email : values) {
            String receivedDateTime = email.path("receivedDateTime").asText("");

            if (receivedAfter != null
                    && !isReceivedAfter(receivedDateTime, receivedAfter)) {
                continue;
            }

            EmailDto parsed = parseEmail(email, mailbox);
            if (parsed != null) {
                emails.add(parsed);
            }
        }

        return emails;
    }

    private EmailDto parseEmail(
            JsonNode email,
            String mailbox
    ) {

        String id =
                email.path("id").asText();

        if (id == null || id.isBlank()) {
            return null;
        }

        String subject =
                email.path("subject").asText();

        String receivedDateTime =
                email.path("receivedDateTime").asText();

        JsonNode sender =
                email
                        .path("from")
                        .path("emailAddress");

        String senderName =
                sender.path("name").asText();

        String senderEmail =
                sender.path("address").asText();

        String senderDomain =
                extractDomain(senderEmail);

        String sourceType =
                classifySource(
                        senderDomain,
                        mailbox
                );

        String body =
                email
                        .path("body")
                        .path("content")
                        .asText("");

        if (body != null && !body.isBlank()) {
            body = Jsoup.parse(body)
                    .body()
                    .text();
        }

        List<String> recipientNames = new ArrayList<>();
        List<String> recipientEmails = new ArrayList<>();
        JsonNode recipients = email.path("toRecipients");

        if (recipients.isArray()) {
            for (JsonNode recipient : recipients) {
                JsonNode emailAddress = recipient.path("emailAddress");

                String recipientName =
                        emailAddress.path("name").asText("");
                String recipientEmail =
                        emailAddress.path("address").asText("");

                if (!recipientName.isBlank()) {
                    recipientNames.add(recipientName);
                }
                if (!recipientEmail.isBlank()) {
                    recipientEmails.add(recipientEmail);
                }
            }
        }

        return new EmailDto(
                id,
                subject,
                senderName,
                senderEmail,
                senderDomain,
                sourceType,
                receivedDateTime,
                body,
                recipientNames,
                recipientEmails,
                mailbox,
                null
        );
    }

    private boolean isReceivedAfter(
            String receivedDateTime,
            Instant boundary
    ) {
        if (receivedDateTime == null || receivedDateTime.isBlank()) {
            return false;
        }

        try {
            return OffsetDateTime.parse(receivedDateTime)
                    .toInstant()
                    .isAfter(boundary);
        } catch (Exception exception) {
            return false;
        }
    }

    private JsonNode readTree(
            String response,
            String message
    ) {
        try {
            return objectMapper.readTree(response);
        } catch (Exception exception) {
            throw new RuntimeException(message, exception);
        }
    }

    /**
     * Get a single email by its Microsoft Graph message ID.
     */
    public EmailDto getEmailById(String emailId) {

    if (emailId == null || emailId.isBlank()) {

        throw new IllegalArgumentException(
                "Email ID must not be null or blank"
        );
    }

    String response = restClient
            .get()
            .uri(uriBuilder -> uriBuilder
                    .path("/me/messages/{emailId}")
                    .queryParam("$select", "id,subject,from,receivedDateTime,bodyPreview,toRecipients,ccRecipients")
                    .build(emailId))
            .header(
                    "Authorization",
                    "Bearer " + getAccessToken()
            )
            .retrieve()
            .body(String.class);

    try {

        JsonNode email =
                objectMapper.readTree(response);

        String id =
                email.path("id").asText();

        String subject =
                email.path("subject").asText();

        String receivedDateTime =
                email.path("receivedDateTime").asText();

        String bodyPreview =
                email.path("bodyPreview").asText();

        JsonNode sender =
                email
                        .path("from")
                        .path("emailAddress");

        String senderName =
                sender.path("name").asText();

        String senderEmail =
                sender.path("address").asText();

        String senderDomain =
                extractDomain(senderEmail);

        String mailbox =
                getConnectedMailbox().email();

        String sourceType =
                classifySource(
                        senderDomain,
                        mailbox
                );

        List<String> toRecipients =
                parseRecipients(
                        email.path("toRecipients")
                );

        List<String> ccRecipients =
                parseRecipients(
                        email.path("ccRecipients")
                );

        return new EmailDto(
                id,
                subject,
                senderName,
                senderEmail,
                senderDomain,
                sourceType,
                receivedDateTime,
                bodyPreview,
                toRecipients,
                ccRecipients,
                mailbox,
                null
        );

    } catch (AuthenticationRequiredException e) {

        // Preserve the authentication failure.
        throw e;

    } catch (Exception e) {

        throw new RuntimeException(
                "Unable to parse Microsoft Graph email response",
                e
        );
    }
}

    /**
     * Parse recipient objects from Microsoft Graph.
     */
    private List<String> parseRecipients(
            JsonNode recipients
    ) {

        List<String> result =
                new ArrayList<>();

        if (recipients == null
                || !recipients.isArray()) {

            return result;
        }

        for (JsonNode recipient : recipients) {

            JsonNode emailAddress =
                    recipient.path("emailAddress");

            String name =
                    emailAddress
                            .path("name")
                            .asText();

            String address =
                    emailAddress
                            .path("address")
                            .asText();

            if (address == null
                    || address.isBlank()) {

                continue;
            }

            if (name == null
                    || name.isBlank()) {

                result.add(address);

            } else {

                result.add(
                        name + " <" + address + ">"
                );
            }
        }

        return result;
    }

    /**
     * Extract domain from an email address.
     */
    private String extractDomain(
            String email
    ) {

        if (email == null || email.isBlank()) {

            return "unknown";
        }

        int atIndex =
                email.lastIndexOf("@");

        if (atIndex == -1) {

            return "unknown";
        }

        return email
                .substring(atIndex + 1)
                .toLowerCase();
    }

    /**
     * Classify the sender based on email domain.
     */
    private String classifySource(
            String senderDomain,
            String mailbox
    ) {

        if (senderDomain == null) {

            return "EXTERNAL";
        }

        /*
         * Internal company email.
         */
        if (senderDomain.equalsIgnoreCase(
                organizationDomain
        )) {

            return "INTERNAL";
        }

        /*
         * Gmail sender.
         */
        if ("gmail.com".equalsIgnoreCase(
                senderDomain
        )) {

            return "GMAIL";
        }

        /*
         * Microsoft consumer email.
         */
        if ("outlook.com".equalsIgnoreCase(
                senderDomain
        )
                || "hotmail.com".equalsIgnoreCase(
                senderDomain
        )
                || "live.com".equalsIgnoreCase(
                senderDomain
        )) {

            return "MICROSOFT";
        }

        return "EXTERNAL";
    }

    /**
     * Group inbox emails by sender domain.
     */
    public List<EmailSourceDto> getEmailSources() {

        List<EmailDto> emails =
                getDashboardInboxEmails();

        Map<String, List<EmailDto>> grouped =
                emails.stream()
                        .collect(
                                Collectors.groupingBy(
                                        EmailDto::senderDomain,
                                        LinkedHashMap::new,
                                        Collectors.toList()
                                )
                        );

        return grouped.entrySet()
                .stream()
                .map(entry -> {

                    String domain =
                            entry.getKey();

                    String sourceType =
                            entry.getValue()
                                    .get(0)
                                    .sourceType();

                    long count =
                            entry.getValue()
                                    .size();

                    return new EmailSourceDto(
                            domain,
                            sourceType,
                            count
                    );
                })
                .toList();
    }
}