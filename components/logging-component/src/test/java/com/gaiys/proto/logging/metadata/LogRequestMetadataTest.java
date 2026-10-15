package com.gaiys.proto.logging.metadata;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.logging.document.LogDocument;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;

class LogRequestMetadataTest {
    @Test
    void headersUseAllowlistAndDoNotRetainAuthenticationOrUrlQuerySecrets() {
        String secret = UUID.randomUUID().toString();
        var headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + secret);
        headers.add("Cookie", "session=" + secret);
        headers.add("X-Access-Token", secret);
        headers.add("X-Client-Secret", secret);
        headers.add("Referer", "https://example.test/login?credential=" + secret);
        headers.add("User-Agent", "test https://example.test/callback?credential%3D" + secret);
        headers.add("Content-Type", "application/json");
        var safe = LogRequestMetadata.headers(headers);
        assertEquals(Set.of("user-agent", "content-type"), safe.keySet());
        assertEquals(List.of("application/json"), safe.get("content-type"));
        assertFalse(safe.toString().contains(secret));
    }

    @Test
    void parameterNamesNormalizeSensitiveVariantsAndNestedUrlsAreRedacted() {
        String secret = UUID.randomUUID().toString();
        var parameters = new LinkedMultiValueMap<String, String>();
        for (String name :
                List.of(
                        "Cookie",
                        "AUTHORIZATION",
                        "Db-PassWord",
                        "Access_TOKEN",
                        "Client-Secret",
                        "Credential",
                        "credentials",
                        "api.key",
                        "KEY",
                        "Verify-Code",
                        "Encrypt-ID")) parameters.add(name, secret);
        parameters.add(
                "next", "https://example.test/callback?credential%3D" + secret + "#" + secret);
        parameters.add("note", "password=" + secret);
        parameters.add("pageIndex", "1");
        var safe = LogRequestMetadata.parameters(parameters);
        for (String name : parameters.keySet())
            if (!Set.of("next", "note", "pageIndex").contains(name))
                assertEquals(List.of("[REDACTED]"), safe.get(name), name);
        assertEquals(List.of("1"), safe.get("pageIndex"));
        assertFalse(safe.toString().contains(secret));
    }

    @Test
    void limitsFieldsValuesAndUtf8PayloadWithoutSplittingUnicode() throws Exception {
        var source = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i < 100; i++)
            source.put("field" + i, Collections.nCopies(8, "😀".repeat(1000)));
        var safe = LogRequestMetadata.parameters(source);
        assertTrue(safe.size() <= 32);
        int size = 0;
        for (var entry : safe.entrySet()) {
            size += entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            assertTrue(entry.getValue().size() <= 4);
            for (String value : entry.getValue()) {
                int bytes = value.getBytes(StandardCharsets.UTF_8).length;
                assertTrue(bytes <= 512);
                assertFalse(
                        !value.isEmpty()
                                && Character.isHighSurrogate(value.charAt(value.length() - 1)));
                size += bytes;
            }
        }
        assertTrue(size <= 4096);
        assertTrue(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(safe).length
                        <= 4096);
        var escaped = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i < 100; i++) escaped.add("field" + i, "\u0000\\\"".repeat(1000));
        assertTrue(
                new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsBytes(LogRequestMetadata.parameters(escaped))
                                .length
                        <= 4096);
    }

    @Test
    void urlQueriesAndFragmentsAreRedactedAcrossPercentEncodingLayers() throws Exception {
        String secret = UUID.randomUUID().toString();
        for (String raw :
                List.of(
                        "https://example.test/call+back?credential=" + secret,
                        "https://example.test/call+back#" + secret,
                        "HTTPS://example.test/call+back?credential%3D" + secret + "#" + secret)) {
            String value = raw;
            for (int depth = 0; depth <= 4; depth++) {
                for (String variant :
                        List.of(
                                value,
                                java.util.regex.Pattern.compile("%[0-9A-Fa-f]{2}")
                                        .matcher(value)
                                        .replaceAll(
                                                match -> match.group().toLowerCase(Locale.ROOT)))) {
                    var parameters = new LinkedMultiValueMap<String, String>();
                    parameters.add("next", variant);
                    var safe = LogRequestMetadata.parameters(parameters);
                    assertFalse(safe.toString().contains(secret), "depth=" + depth);
                    assertTrue(safe.get("next").getFirst().contains("call+back"));
                    assertTrue(safe.get("next").getFirst().contains("[REDACTED]"));
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    LogDocument restored =
                            mapper.readValue(
                                    mapper.writeValueAsString(
                                            LogDocument.builder().parameters(safe).build()),
                                    LogDocument.class);
                    assertFalse(restored.getParameters().toString().contains(secret));
                }
                value = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
            }
        }
    }

    @Test
    void validEncodedPlainTextKeepsItsDisplayValueAndLiteralPlus() {
        for (String value :
                List.of(
                        "ordinary+text",
                        "hello%20world%2Bname",
                        "%E4%BD%A0%E5%A5%BD%2B",
                        "progress%3D100%25",
                        "plain display text",
                        "100%",
                        "%engineer%",
                        "%",
                        "%0",
                        "%ZZ")) {
            var parameters = new LinkedMultiValueMap<String, String>();
            parameters.add("note", value);
            assertEquals(List.of(value), LogRequestMetadata.parameters(parameters).get("note"));
        }
    }

    @Test
    void malformedEncodingAndExcessiveLayersFailClosed() {
        for (String value :
                List.of(
                        "%C3%28",
                        "%FF",
                        "%E0%80%AF",
                        "%41%２F",
                        "https://example.test/%ZZ",
                        "https%3A%2F%2Fexample.test%2Fcallback%3Fcredential%3Dprobe%ZZ",
                        "%252Fcallback%253Fcredential%253Dprobe%25ZZ")) {
            var parameters = new LinkedMultiValueMap<String, String>();
            parameters.add("note", value);
            assertEquals(
                    List.of("[REDACTED]"), LogRequestMetadata.parameters(parameters).get("note"));
        }
        String value = "https://example.test/callback?credential=probe";
        for (int depth = 0; depth < 5; depth++)
            value = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add("next", value);
        assertEquals(List.of("[REDACTED]"), LogRequestMetadata.parameters(parameters).get("next"));
    }

    @Test
    void percentNormalizationRetainsTheCompactJsonBudget() throws Exception {
        String secret = UUID.randomUUID().toString();
        String value =
                "\u0000\\\"😀".repeat(1000) + " https://example.test/callback?credential=" + secret;
        for (int depth = 0; depth < 4; depth++)
            value = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        var parameters = new LinkedMultiValueMap<String, String>();
        for (int field = 0; field < 100; field++)
            parameters.put("field" + field, Collections.nCopies(8, value));
        var safe = LogRequestMetadata.parameters(parameters);
        byte[] json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(safe);
        assertTrue(json.length <= 4096);
        assertFalse(new String(json, StandardCharsets.UTF_8).contains(secret));
        assertTrue(safe.size() <= 32);
        safe.values().forEach(values -> assertTrue(values.size() <= 4));
    }

    @Test
    void mixedPlainAndEncodedSensitiveFragmentsAreAllRedacted() throws Exception {
        String plain = UUID.randomUUID().toString();
        String query = UUID.randomUUID().toString();
        String fragment = UUID.randomUUID().toString();
        for (int depth = 1; depth <= 4; depth++) {
            String next = "https://example.test/call+back?credential=" + query;
            String redirect = "https://example.test/redirect#" + fragment;
            for (int layer = 0; layer < depth; layer++)
                next = java.net.URLEncoder.encode(next, StandardCharsets.UTF_8);
            for (int layer = 0; layer < 5 - depth; layer++)
                redirect = java.net.URLEncoder.encode(redirect, StandardCharsets.UTF_8);
            var parameters = new LinkedMultiValueMap<String, String>();
            parameters.add(
                    "note",
                    "password="
                            + plain
                            + " next="
                            + next
                            + " redirect="
                            + redirect
                            + " finish=ordinary+text");
            var safe = LogRequestMetadata.parameters(parameters);
            String result = safe.get("note").getFirst();
            assertFalse(result.contains(plain));
            assertFalse(result.contains(query));
            assertFalse(result.contains(fragment));
            assertTrue(result.contains("password=[REDACTED]"));
            assertTrue(result.contains("finish=ordinary+text"));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            LogDocument restored =
                    mapper.readValue(
                            mapper.writeValueAsString(
                                    LogDocument.builder().parameters(safe).build()),
                            LogDocument.class);
            assertFalse(restored.getParameters().toString().contains(query));
            assertFalse(restored.getParameters().toString().contains(fragment));
        }
    }

    @Test
    void plainRedactionDoesNotSkipMalformedOrOvernestedEncodedSiblings() {
        String plain = UUID.randomUUID().toString();
        String excessive = "https://example.test/callback?credential=probe";
        for (int depth = 0; depth < 5; depth++)
            excessive = java.net.URLEncoder.encode(excessive, StandardCharsets.UTF_8);
        for (String candidate : List.of("%FF", "%41%ZZ", "%C3%28", "%25FF", excessive)) {
            var parameters = new LinkedMultiValueMap<String, String>();
            parameters.add("note", "password=" + plain + " next=" + candidate);
            assertEquals(
                    List.of("[REDACTED]"), LogRequestMetadata.parameters(parameters).get("note"));
        }
        for (String ordinary : List.of("100%", "%engineer%")) {
            var parameters = new LinkedMultiValueMap<String, String>();
            parameters.add("note", "password=" + plain + " next=" + ordinary);
            assertEquals(
                    List.of("password=[REDACTED] next=" + ordinary),
                    LogRequestMetadata.parameters(parameters).get("note"));
        }
    }
}
