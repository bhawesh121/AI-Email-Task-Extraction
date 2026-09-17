package com.poc.aiassistant.util;

/**
 * Canonical sender-identity normalization used for logical-task
 * duplicate scoping.
 *
 * Rule (per business requirement): trim whitespace + lower-case.
 * We deliberately do NOT use display name, only the address itself,
 * so "Alice@Company.com" and "alice@company.com" resolve to the
 * same scope.
 *
 * This must match the normalization used in
 * V14__add_normalized_sender.sql (lower(trim(source_sender))),
 * otherwise backfilled data and newly-created rows would diverge.
 */
public final class SenderNormalizer {

    private SenderNormalizer() {
    }

    public static String normalize(String senderEmail) {

        if (senderEmail == null) {
            return null;
        }

        String trimmed = senderEmail.trim();

        if (trimmed.isEmpty()) {
            return null;
        }

        return trimmed.toLowerCase();
    }
}
