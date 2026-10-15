package com.gaiys.proto.system.security.encoder;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StrongPasswordEncoderTest {
    private final StrongPasswordEncoder encoder = new StrongPasswordEncoder();

    @Test
    void standardHashIsDeterministicAndSalted() {
        String salt = java.util.UUID.randomUUID().toString().replace("-", "");
        String password = java.util.UUID.randomUUID().toString();
        String hash = encoder.encode(password, salt);
        assertTrue(hash.startsWith("pbkdf2-sha256$600000$"));
        assertEquals(hash, encoder.encode(password, salt));
        assertNotEquals(
                hash,
                encoder.encode(password, java.util.UUID.randomUUID().toString().replace("-", "")));
        assertNotEquals(hash, encoder.encode(password + "x", salt));
        assertFalse(hash.contains(password));
    }

    @Test
    void rejectsMissingPasswordOrUnsafeSalt() {
        assertThrows(
                IllegalArgumentException.class, () -> encoder.encode(null, "0123456789abcdef"));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode("example", "short"));
    }
}
