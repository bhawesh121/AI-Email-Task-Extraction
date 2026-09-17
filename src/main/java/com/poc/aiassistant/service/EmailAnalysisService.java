package com.poc.aiassistant.service;

import java.util.Locale;

import org.springframework.stereotype.Service;

import com.poc.aiassistant.dto.EmailMessageDto;

@Service
public class EmailAnalysisService {

    public boolean isActionable(EmailMessageDto email) {

        if (email == null) {
            return false;
        }

        String subject = normalize(email.subject());
        String body = normalize(email.bodyPreview());

        String content = subject + " " + body;

        // ---------------------------------------------------------
        // 1. Ignore obvious automated/system emails
        // ---------------------------------------------------------

        if (isAutomatedSender(email.senderEmail())) {
            return false;
        }

        // ---------------------------------------------------------
        // 2. Ignore obvious marketing/newsletter emails
        // ---------------------------------------------------------

        if (isMarketingEmail(subject, body)) {
            return false;
        }

        // ---------------------------------------------------------
        // 3. Ignore common security/system notifications
        // ---------------------------------------------------------

        if (isSystemNotification(subject, body)) {
            return false;
        }

        // ---------------------------------------------------------
        // 4. Look for genuine human-directed work requests
        // ---------------------------------------------------------

        return containsWorkRequest(content);
    }

    private String normalize(String value) {

        if (value == null) {
            return "";
        }

        return value
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isAutomatedSender(String senderEmail) {

        if (senderEmail == null || senderEmail.isBlank()) {
            return false;
        }

        String sender = senderEmail
                .toLowerCase(Locale.ROOT)
                .trim();

        return sender.startsWith("no-reply@")
                || sender.startsWith("noreply@")
                || sender.startsWith("donotreply@")
                || sender.startsWith("do-not-reply@")
                || sender.contains("account-security")
                || sender.contains("notification@")
                || sender.contains("notifications@")
                || sender.contains("mailer-daemon@");
    }

    private boolean isMarketingEmail(
            String subject,
            String body
    ) {

        String content = subject + " " + body;

        String[] marketingSignals = {

                "newsletter",
                "unsubscribe",
                "special offer",
                "limited time offer",
                "save big",
                "exclusive offer",
                "discount",
                "sale ends",
                "shop now",
                "buy now",
                "renew today",
                "learn more",
                "promotional",
                "promotion",
                "deal of the day",
                "black friday",
                "cyber monday",
                "earn rewards",
                "gift card"
        };

        for (String signal : marketingSignals) {

            if (content.contains(signal)) {
                return true;
            }
        }

        return false;
    }

    private boolean isSystemNotification(
            String subject,
            String body
    ) {

        String content = subject + " " + body;

        String[] notificationSignals = {

                "new app(s) connected",
                "new app connected",
                "security alert",
                "security notification",
                "sign-in alert",
                "new sign-in",
                "password changed",
                "account activity",
                "account notification",
                "terms of use",
                "services agreement",
                "your account",
                "automated message",
                "this is an automated"
        };

        for (String signal : notificationSignals) {

            if (content.contains(signal)) {
                return true;
            }
        }

        return false;
    }

    private boolean containsWorkRequest(String content) {

        /*
         * Important:
         *
         * Do NOT treat "please" alone as an actionable signal.
         *
         * We require a work/action verb together with a request
         * pattern.
         */

        String[] workSignals = {

                "please prepare",
                "please review",
                "please send",
                "please complete",
                "please investigate",
                "please create",
                "please update",
                "please fix",
                "please deploy",
                "please check",
                "please analyze",
                "please verify",
                "please test",
                "please share",
                "please provide",
                "please schedule",
                "please arrange",

                "need you to prepare",
                "need you to review",
                "need you to send",
                "need you to complete",
                "need you to investigate",
                "need you to create",
                "need you to update",
                "need you to fix",
                "need you to deploy",
                "need you to check",

                "can you prepare",
                "can you review",
                "can you send",
                "can you complete",
                "can you investigate",
                "can you create",
                "can you update",
                "can you fix",
                "can you deploy",

                "could you prepare",
                "could you review",
                "could you send",
                "could you complete",
                "could you investigate",
                "could you create",
                "could you update",
                "could you fix",

                "follow up with",
                "follow-up with",
                "follow up on",
                "follow-up on"

        };

        for (String signal : workSignals) {

            if (content.contains(signal)) {
                return true;
            }
        }

        return false;
    }
}