package com.gaiys.proto.logging.encoder;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MarkerFactory;
import org.slf4j.event.KeyValuePair;

class MaskedPatternLayoutEncoderTest {
    private final LoggerContext context = new LoggerContext();

    @AfterEach
    void close() {
        context.stop();
    }

    @Test
    void sqlMaskingKeepsTheTextPrefixAndOriginalEvent() {
        var event = event("select password from s_user");
        String output = encode("%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX,Asia/Shanghai} %5p %m%n", event);
        assertEquals("2026-10-10T11:53:55.834+08:00  INFO [SQL REDACTED]\n", output);
        assertEquals("select password from s_user", event.getFormattedMessage());
    }

    @Test
    void defaultMessageIncludesSafeStructuredAuditDetails() {
        String secret = UUID.randomUUID().toString();
        var event = event("audit");
        event.setKeyValuePairs(
                List.of(
                        new KeyValuePair("action", "project.create"),
                        new KeyValuePair("status", 200),
                        new KeyValuePair("accessKey", secret),
                        new KeyValuePair("context", Map.of("salt", secret, "actor", "ai"))));
        String output = encode("%m%n", event);
        assertTrue(output.contains("audit action=\"project.create\" status=\"200\""), output);
        assertTrue(output.contains("actor=ai"), output);
        assertTrue(output.contains("salt=[REDACTED]"), output);
        assertFalse(output.contains(secret), output);
        assertEquals(secret, event.getKeyValuePairs().get(2).value);
    }

    @Test
    void explicitPairConverterInsideCompositeDoesNotDuplicateFields() {
        var event = event("access");
        event.setKeyValuePairs(List.of(new KeyValuePair("status", 200)));
        String output = encode("%msg %replace(%kvp){'200','OK'}%n", event);
        assertEquals("access status=\"OK\"\n", output);
    }

    @Test
    void patternOverridesMaskAliasesMdcPairsNamesMarkersAndThrowableGraph() {
        String secret = UUID.randomUUID().toString();
        var event =
                event(
                        "password={} /Users/example/private.zip",
                        "password=" + secret,
                        "token=" + secret,
                        Map.of("Authorization", secret, "operation", "upload"));
        event.setArgumentArray(new Object[] {secret});
        var marker = MarkerFactory.getDetachedMarker("apiKey=" + secret);
        marker.add(MarkerFactory.getDetachedMarker("token=" + secret));
        event.addMarker(marker);
        event.setKeyValuePairs(
                List.of(
                        new KeyValuePair("payload", List.of(Map.of("privateKey", secret))),
                        new KeyValuePair("status", 400)));
        var failure =
                new IllegalStateException(
                        "password=" + secret,
                        new IllegalArgumentException("select '" + secret + "' from s_user"));
        failure.addSuppressed(new java.io.IOException("/Users/example/" + secret));
        event.setThrowableProxy(new ThrowableProxy(failure));
        String output = encode("%message %mdc %kvp %logger %thread %marker%n%exception", event);
        assertFalse(output.contains(secret), output);
        assertFalse(output.contains("/Users/example"), output);
        assertFalse(output.contains("select"), output);
        assertFalse(output.contains("MaskedPatternLayoutEncoderTest.java"), output);
        assertTrue(output.contains("java.lang.IllegalStateException"), output);
        assertTrue(output.contains("Caused by: java.lang.IllegalArgumentException"), output);
        assertTrue(output.contains("Suppressed: java.io.IOException"), output);
        assertTrue(output.contains("operation=upload"), output);
        assertTrue(output.contains("status=\"400\""), output);
        assertEquals(secret, event.getArgumentArray()[0]);
        assertEquals(secret, event.getMDCPropertyMap().get("Authorization"));
    }

    @Test
    void automaticallyAppendedThrowableConverterOnlySeesSanitizedTypes() {
        String secret = UUID.randomUUID().toString();
        var event = event("Application run failed");
        event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("token=" + secret)));
        String output = encode("%msg%n", event);
        assertTrue(output.contains("java.lang.IllegalStateException"), output);
        assertFalse(output.contains(secret), output);
        assertFalse(output.contains("MaskedPatternLayoutEncoderTest.java"), output);
    }

    private String encode(String pattern, LoggingEvent event) {
        var encoder = new MaskedPatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.setPattern(pattern);
        encoder.start();
        try {
            return new String(encoder.encode(event), StandardCharsets.UTF_8);
        } finally {
            encoder.stop();
        }
    }

    @Test
    void unknownStructuredObjectsCannotLeakCredentialsThroughToString() {
        String secret = UUID.randomUUID().toString();
        record Credentials(String password) {
            @Override
            public String toString() {
                return password;
            }
        }
        var event = event("audit");
        event.setKeyValuePairs(List.of(new KeyValuePair("details", new Credentials(secret))));
        String output = encode("%m %kvp%n", event);
        assertFalse(output.contains(secret), output);
        assertTrue(output.contains("details=\"[REDACTED]\""), output);
    }

    private LoggingEvent event(String message) {
        return event(message, "main", "com.gaiys.proto.ProtoBridgeApplication");
    }

    private LoggingEvent event(String message, String thread, String logger) {
        return event(message, thread, logger, Map.of());
    }

    private LoggingEvent event(
            String message, String thread, String logger, Map<String, String> mdc) {
        var event = new LoggingEvent();
        event.setLoggerContextRemoteView(context.getLoggerContextRemoteView());
        event.setMDCPropertyMap(mdc);
        event.setTimeStamp(Instant.parse("2026-10-10T03:53:55.834Z").toEpochMilli());
        event.setLevel(Level.INFO);
        event.setLoggerName(logger);
        event.setThreadName(thread);
        event.setMessage(message);
        return event;
    }
}
