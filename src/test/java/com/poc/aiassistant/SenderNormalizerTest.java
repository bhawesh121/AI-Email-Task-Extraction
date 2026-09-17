package com.poc.aiassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.poc.aiassistant.util.SenderNormalizer;

class SenderNormalizerTest {

    @Test
    void lowerCasesAndTrimsSenderAddress() {
        assertEquals(
                "alice@company.com",
                SenderNormalizer.normalize("  Alice@Company.com  ")
        );
    }

    @Test
    void differentCasingNormalizesToSameValue() {
        assertEquals(
                SenderNormalizer.normalize("Alice@Company.com"),
                SenderNormalizer.normalize("alice@company.com")
        );
    }

    @Test
    void nullSenderNormalizesToNull() {
        assertNull(SenderNormalizer.normalize(null));
    }

    @Test
    void blankSenderNormalizesToNull() {
        assertNull(SenderNormalizer.normalize("   "));
    }

    @Test
    void doesNotAlterLocalPartCasingSemanticsBeyondLowercasing() {
        // We intentionally do a blunt lower-case, matching the
        // documented business rule ("trim whitespace + normalize
        // case"), not RFC 5321 local-part case sensitivity nuances.
        assertEquals(
                "bob.smith+tasks@company.com",
                SenderNormalizer.normalize("Bob.Smith+Tasks@Company.com")
        );
    }
}
