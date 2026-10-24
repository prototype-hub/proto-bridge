package com.gaiys.proto.prototypehub.service.keys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/** 不同用途使用域分隔；只保存摘要，绝不将HttpOnly原始凭证返回JSON。 */
public final class PrototypeReviewKeys {
    private static final SecureRandom RANDOM = new SecureRandom();

    private PrototypeReviewKeys() {}

    public static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String code() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    public static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public static boolean matches(String expected, String actual) {
        return expected != null
                && actual != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        actual.getBytes(StandardCharsets.UTF_8));
    }

    public static String email(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    public static String guest(String projectId, String email) {
        return digest("review-guest-v1:" + projectId.length() + ":" + projectId + email);
    }

    public static String csrf(String cookie) {
        return digest("review-csrf-v1:" + cookie);
    }

    public static String cookie(String raw) {
        return raw != null && raw.matches("[A-Za-z0-9_-]{43}") ? raw : random();
    }

    public static String masked(String email) {
        int at = email.indexOf('@');
        return email.substring(0, 1) + "***@" + email.substring(at + 1);
    }
}
