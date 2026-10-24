package com.gaiys.proto.prototypehub.service.keys;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class PrototypeReviewKeysTest {
    @Test
    void canonicalEmailHasStableProjectScopedIdentity() {
        String email = PrototypeReviewKeys.email("  Alice@EXAMPLE.com  ");
        assertEquals("alice@example.com", email);
        assertEquals(
                PrototypeReviewKeys.guest("p", email),
                PrototypeReviewKeys.guest("p", "alice@example.com"));
        assertNotEquals(
                PrototypeReviewKeys.guest("p", email), PrototypeReviewKeys.guest("other", email));
        assertNotEquals(PrototypeReviewKeys.guest("ab", "c"), PrototypeReviewKeys.guest("a", "bc"));
    }

    @Test
    void tokensAreHighEntropyAndCsrfIsDomainSeparated() {
        String first = PrototypeReviewKeys.random();
        assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(first, PrototypeReviewKeys.random());
        assertEquals(first, PrototypeReviewKeys.cookie(first));
        assertNotEquals(first, PrototypeReviewKeys.cookie("invalid"));
        assertNotEquals(PrototypeReviewKeys.digest(first), PrototypeReviewKeys.csrf(first));
        assertTrue(
                PrototypeReviewKeys.matches(
                        PrototypeReviewKeys.csrf(first), PrototypeReviewKeys.csrf(first)));
        assertFalse(PrototypeReviewKeys.matches(first, null));
    }

    @Test
    void codesRemainSixAsciiDigitsUnderNonLatinLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"));
            for (int i = 0; i < 100; i++)
                assertTrue(PrototypeReviewKeys.code().matches("[0-9]{6}"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void publicEmailIsMasked() {
        assertEquals("a***@example.com", PrototypeReviewKeys.masked("alice@example.com"));
    }
}
