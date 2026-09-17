package com.poc.aiassistant.util;

/**
 * Canonical employee display-name derivation.
 *
 * The canonical label is derived from the mailbox local-part rather than
 * Microsoft Graph displayName so an editable Graph display label cannot
 * fragment tasks across multiple assignee names.
 */
public final class EmployeeNameNormalizer {

    private EmployeeNameNormalizer() {
    }

    public static String deriveNameFromEmail(String email) {
        if (email == null) {
            return null;
        }

        String trimmed = email.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        int atIndex = trimmed.indexOf('@');
        String localPart = atIndex > 0
                ? trimmed.substring(0, atIndex)
                : trimmed;

        String canonical = localPart.trim().toLowerCase();
        return canonical.isEmpty() ? null : canonical;
    }
}
