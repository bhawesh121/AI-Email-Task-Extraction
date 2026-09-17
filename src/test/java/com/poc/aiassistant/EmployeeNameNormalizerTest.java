package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.poc.aiassistant.util.EmployeeNameNormalizer;

class EmployeeNameNormalizerTest {

    @Test
    void derivesLowerCasedLocalPartFromEmail() {
        assertEquals(
                "merchant1",
                EmployeeNameNormalizer.deriveNameFromEmail("merchant1@jakgroup.in")
        );
    }

    @Test
    void differentDisplayNameFormattingOfSameMailboxStillNormalizesIdentically() {
        // This is the exact bug: "Merchant 1" vs "Merchant1" used to come
        // from Graph displayName and diverge. The email itself is the
        // stable identity, so deriving from it must be deterministic.
        assertEquals(
                EmployeeNameNormalizer.deriveNameFromEmail("Merchant1@JakGroup.in"),
                EmployeeNameNormalizer.deriveNameFromEmail("  merchant1@jakgroup.in  ")
        );
    }

    @Test
    void trimsWhitespaceAroundAddress() {
        assertEquals(
                "merchant2",
                EmployeeNameNormalizer.deriveNameFromEmail("  merchant2@jakgroup.in  ")
        );
    }

    @Test
    void nullEmailProducesNullName() {
        assertNull(EmployeeNameNormalizer.deriveNameFromEmail(null));
    }

    @Test
    void blankEmailProducesNullName() {
        assertNull(EmployeeNameNormalizer.deriveNameFromEmail("   "));
    }

    @Test
    void addressWithNoAtSignFallsBackToWholeValueLowercased() {
        // Defensive: getUserEmail() can fall back to userPrincipalName,
        // which is always email-shaped in practice, but we should not
        // throw on malformed input either way.
        assertEquals(
                "notanemail",
                EmployeeNameNormalizer.deriveNameFromEmail("NotAnEmail")
        );
    }
}