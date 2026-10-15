package com.gaiys.proto.logging.dispatcher;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.gaiys.proto.logging.event.AuditEvent;
import com.gaiys.proto.logging.masker.SensitiveValueMasker;
import com.gaiys.proto.logging.sink.AuditSink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import reactor.core.publisher.Mono;

class AuditLoggingTest {
    @Test
    void encodedJsonMasksSecretsCapabilitiesAndPaths() {
        String secret = UUID.randomUUID().toString();
        var context = new LoggerContext();
        var encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.setIncludeContext(false);
        var decorator = new MaskingJsonGeneratorDecorator();
        decorator.addValueMasker(new SensitiveValueMasker());
        decorator.start();
        encoder.setJsonGeneratorDecorator(decorator);
        encoder.start();
        var event = new LoggingEvent();
        event.setMDCPropertyMap(java.util.Map.of());
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerName("test");
        event.setLevel(Level.INFO);
        event.setLoggerContextRemoteView(context.getLoggerContextRemoteView());
        event.setThreadName("test");
        event.setMessage(
                "password="
                        + secret
                        + " token="
                        + secret
                        + " Authorization=Bearer "
                        + secret
                        + " /Users/example/private/file.zip /api/prototype-hub/runtime/version/"
                        + secret
                        + "/index.html");
        event.setKeyValuePairs(
                List.of(
                        new KeyValuePair("accessKey", secret),
                        new KeyValuePair("Cookie", secret),
                        new KeyValuePair("previewKey", secret),
                        new KeyValuePair("apiKey", secret),
                        new KeyValuePair("privateKey", secret),
                        new KeyValuePair("verifyCode", secret),
                        new KeyValuePair("action", "version.upload")));
        String json = new String(encoder.encode(event), java.nio.charset.StandardCharsets.UTF_8);
        encoder.stop();
        context.stop();
        assertFalse(json.contains(secret), json);
        assertFalse(json.contains("/Users/example"));
        assertTrue(json.contains("[REDACTED]"));
        assertTrue(json.contains("version.upload"));
    }

    @Test
    void encodedExceptionsKeepCauseTypesWithoutMessagesSqlPathsOrSecrets() throws Exception {
        String secret = UUID.randomUUID().toString();
        var context = new LoggerContext();
        var encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.setIncludeContext(false);
        var decorator = new MaskingJsonGeneratorDecorator();
        decorator.addValueMasker(new SensitiveValueMasker());
        decorator.start();
        encoder.setJsonGeneratorDecorator(decorator);
        encoder.start();
        var event = new LoggingEvent();
        event.setMDCPropertyMap(java.util.Map.of());
        event.setTimeStamp(System.currentTimeMillis());
        event.setLoggerName("test");
        event.setLevel(Level.ERROR);
        event.setLoggerContextRemoteView(context.getLoggerContextRemoteView());
        event.setThreadName("test");
        event.setMessage("Application run failed");
        var cause =
                new IllegalArgumentException(
                        "select '" + secret + "' from s_user; /Users/example/private/file.zip");
        var failure = new IllegalStateException("password=" + secret, cause);
        failure.addSuppressed(new java.io.IOException("token=" + secret));
        event.setThrowableProxy(new ThrowableProxy(failure));
        var json =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(encoder.encode(event));
        encoder.stop();
        context.stop();
        assertTrue(json.path("stack_trace").asText().contains("java.lang.IllegalStateException"));
        assertTrue(
                json.path("stack_trace").asText().contains("java.lang.IllegalArgumentException"));
        assertTrue(json.path("stack_trace").asText().contains("java.io.IOException"));
        assertEquals("Application run failed", json.path("message").asText());
        assertFalse(json.toString().contains(secret));
        assertFalse(json.toString().contains("select"));
        assertFalse(json.toString().contains("/Users/example"));
        assertFalse(json.path("stack_trace").asText().contains("AuditLoggingTest.java"));
    }

    @Test
    void renderedSqlAndCapabilityLinksAreRedacted() {
        String secret = UUID.randomUUID().toString();
        assertEquals(
                "[SQL REDACTED]",
                SensitiveValueMasker.redact(
                        "==> Error: update table set preview_key = '"
                                + secret
                                + "' where id='version'"));
        for (String prefix :
                List.of("/prototype-hub", "/api/prototype-hub", "/protohub", "/api/protohub")) {
            for (String route : List.of("/runtime/version/", "/public/shares/")) {
                assertFalse(
                        SensitiveValueMasker.redact(prefix + route + secret + "/key/index.html")
                                .contains(secret));
            }
        }
        assertEquals("[PATH]", SensitiveValueMasker.redact("/private/tmp/upload/a.zip"));
        assertEquals("[PATH]", SensitiveValueMasker.redact("/app/data/protohub/file.zip"));
        assertFalse(
                SensitiveValueMasker.redact("ApiKey " + secret + " Basic " + secret)
                        .contains(secret));
        assertFalse(
                SensitiveValueMasker.redact("\"password\":\"" + secret + "\", verifyCode=" + secret)
                        .contains(secret));
    }

    @Test
    void sinkFailureDoesNotPreventOtherSinksOrBlockOffer() throws Exception {
        var metrics = new SimpleMeterRegistry();
        var received = new CountDownLatch(1);
        AuditSink unavailable =
                e -> Mono.error(new IllegalStateException("controlled unavailability"));
        AuditSink available = e -> Mono.fromRunnable(received::countDown);
        var dispatcher = new AuditDispatcher(List.of(unavailable, available), metrics);
        dispatcher.start();
        try {
            long start = System.nanoTime();
            dispatcher.offer(
                    new AuditEvent(
                            "trace",
                            "actor",
                            "project.create",
                            "project",
                            "id",
                            "success",
                            1,
                            System.currentTimeMillis()));
            assertTrue((System.nanoTime() - start) / 1_000_000 < 500);
            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertEquals(1, metrics.counter("proto.audit.sink.failures").count());
        } finally {
            dispatcher.close();
        }
    }

    @Test
    void hungSinkTimesOutAndFallsBack() throws Exception {
        var metrics = new SimpleMeterRegistry();
        var received = new CountDownLatch(1);
        var dispatcher =
                new AuditDispatcher(
                        List.of(e -> Mono.never(), e -> Mono.fromRunnable(received::countDown)),
                        metrics);
        dispatcher.start();
        try {
            dispatcher.offer(
                    new AuditEvent(
                            "trace",
                            "anonymous",
                            "login",
                            "user",
                            null,
                            "HTTP_401",
                            1,
                            System.currentTimeMillis()));
            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertEquals(1, metrics.counter("proto.audit.sink.failures").count());
        } finally {
            dispatcher.close();
        }
    }
}
