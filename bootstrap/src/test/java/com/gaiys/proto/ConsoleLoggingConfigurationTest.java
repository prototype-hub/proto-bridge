package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.logging.encoder.MaskedPatternLayoutEncoder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.ansi.AnsiOutput;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystemProperty;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.boot.logging.logback.LogbackLoggingSystemProperties;
import org.springframework.boot.logging.logback.RollingPolicySystemProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ConsoleLoggingConfigurationTest {
    @TempDir Path directory;
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private final Map<String, String> previousSystemProperties = new LinkedHashMap<>();
    private PrintStream originalOutput;
    private AnsiOutput.Enabled originalAnsi;
    private LoggerContext loggerContext;
    private LogbackLoggingSystem loggingSystem;

    @BeforeEach
    void captureConsole() {
        for (var property : LoggingSystemProperty.values())
            rememberProperty(property.getEnvironmentVariableName());
        for (var property : RollingPolicySystemProperty.values())
            rememberProperty(property.getEnvironmentVariableName());
        rememberProperty("LOG_FILE");
        rememberProperty("LOG_PATH");
        rememberProperty("LOGGED_APPLICATION_NAME");
        originalOutput = System.out;
        originalAnsi = AnsiOutput.getEnabled();
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        AnsiOutput.setEnabled(AnsiOutput.Enabled.NEVER);
        loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.reset();
    }

    @AfterEach
    void restore() {
        loggerContext.stop();
        if (loggingSystem != null) loggingSystem.cleanUp();
        System.setOut(originalOutput);
        AnsiOutput.setEnabled(originalAnsi);
        previousSystemProperties.forEach(
                (key, value) -> {
                    if (value == null) System.clearProperty(key);
                    else System.setProperty(key, value);
                });
    }

    @Test
    void realXmlUsesBootColumnsAndPreservesJsonRollingThresholdsAndAsyncSettings()
            throws Exception {
        var file = directory.resolve("application.log");
        initialize(
                Map.of(
                        "logging.file.name",
                        file.toString(),
                        "logging.level.root",
                        "DEBUG",
                        "logging.charset.console",
                        "ISO-8859-1"));
        var root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        var asyncConsole = (AsyncAppender) root.getAppender("ASYNC_CONSOLE");
        var asyncFile = (AsyncAppender) root.getAppender("ASYNC_FILE");
        assertEquals(2048, asyncConsole.getQueueSize());
        assertEquals(2048, asyncFile.getQueueSize());
        assertEquals(0, asyncConsole.getDiscardingThreshold());
        assertTrue(asyncConsole.isNeverBlock());
        assertEquals(1000, asyncConsole.getMaxFlushTime());
        var consoleEncoder =
                assertInstanceOf(
                        MaskedPatternLayoutEncoder.class,
                        ((ConsoleAppender<?>) asyncConsole.getAppender("CONSOLE")).getEncoder());
        assertEquals(StandardCharsets.ISO_8859_1, consoleEncoder.getCharset());
        var fileAppender = (RollingFileAppender<?>) asyncFile.getAppender("FILE");
        assertTrue(fileAppender.getEncoder() instanceof LogstashEncoder);
        assertNotNull(fileAppender.getRollingPolicy());
        root.callAppenders(event(Level.DEBUG, "debug-only-console"));
        root.callAppenders(event(Level.INFO, "Starting Application using Java 21"));
        loggerContext.stop();
        String text = console.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("debug-only-console"), text);
        String prefix =
                "2026-10-10T11:53:55.834+08:00  INFO "
                        + System.getProperty("PID")
                        + " --- [proto-bridge] [           main] com.gaiys.proto.ProtoBridgeApplication";
        assertTrue(
                text.lines()
                        .anyMatch(
                                line ->
                                        line.startsWith(prefix)
                                                && line.endsWith(
                                                        ": Starting Application using Java 21")),
                text);
        var lines = Files.readAllLines(file);
        assertEquals(1, lines.size(), lines.toString());
        var json = new ObjectMapper().readTree(lines.getFirst());
        assertEquals("INFO", json.path("level").asText());
        assertEquals("Starting Application using Java 21", json.path("message").asText());
    }

    @Test
    void propertyTimezoneAndConsoleThresholdAreAppliedByTheRealConfiguration() throws Exception {
        initialize(
                Map.of(
                        "logging.file.name",
                        directory.resolve("utc.log").toString(),
                        "logging.pattern.dateformat",
                        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX,UTC",
                        "logging.threshold.console",
                        "WARN"));
        var root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.callAppenders(event(Level.INFO, "filtered-info"));
        root.callAppenders(event(Level.WARN, "utc-warning"));
        loggerContext.stop();
        String text = console.toString(StandardCharsets.UTF_8);
        assertFalse(text.contains("filtered-info"), text);
        assertTrue(text.contains("2026-10-10T03:53:55.834Z  WARN"), text);
    }

    @Test
    void bootPatternOverrideStillMasksMessagesMdcAndExceptions() throws Exception {
        String secret = UUID.randomUUID().toString();
        initialize(
                Map.of(
                        "logging.file.name",
                        directory.resolve("override.log").toString(),
                        "logging.pattern.console",
                        "%msg %X{password}%n%ex"));
        var event = event(Level.ERROR, "token=" + secret, Map.of("password", secret));
        event.setThrowableProxy(
                new ThrowableProxy(new IllegalStateException("password=" + secret)));
        loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).callAppenders(event);
        loggerContext.stop();
        String text = console.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("token=[REDACTED] [REDACTED]"), text);
        assertTrue(text.contains("java.lang.IllegalStateException"), text);
        assertFalse(text.contains(secret), text);
        assertFalse(text.contains("ConsoleLoggingConfigurationTest.java"), text);
    }

    @Test
    void realSpringBootStartupEmitsTheRequestedConsoleLayoutWithoutInfrastructure()
            throws Exception {
        // Redirect the optional user-local import to an absent isolated path; register no services.
        var application = new SpringApplication(ConsoleProbe.class);
        application.setMainApplicationClass(ProtoBridgeApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        try (var context =
                application.run(
                        "--spring.config.location=classpath:application.yml",
                        "--PROTO_CONFIG_FILE=" + directory.resolve("absent-user-local.yml"),
                        "--spring.main.web-application-type=none",
                        "--logging.config=classpath:logback-spring.xml",
                        "--spring.application.name=proto-bridge",
                        "--logging.level.com.gaiys.proto=DEBUG",
                        "--logging.file.name=" + directory.resolve("startup.log"),
                        "--spring.profiles.active=default,dev,local",
                        "--spring.output.ansi.enabled=never")) {
            assertNotNull(context);
        }
        loggerContext.stop();
        String text = console.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("[proto-bridge]"), text);
        assertTrue(text.contains("Starting ProtoBridgeApplication using Java"), text);
        assertTrue(text.contains("Running with Spring Boot v3.5.16"), text);
        assertTrue(text.contains("default"), text);
        assertTrue(text.contains("dev"), text);
        assertTrue(text.contains("local"), text);
        assertFalse(text.contains("\"@timestamp\""), text);
        var sample = Path.of("target", "console-logging-startup-sample.log");
        Files.createDirectories(sample.getParent());
        Files.writeString(sample, text);
    }

    private void rememberProperty(String key) {
        if (!previousSystemProperties.containsKey(key))
            previousSystemProperties.put(key, System.getProperty(key));
    }

    private void initialize(Map<String, Object> overrides) throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", overrides));
        var yaml = new YamlPropertySourceLoader();
        // Parse resources only; do not process imports or open the user's actual local
        // configuration.
        for (var source : yaml.load("application", new ClassPathResource("application.yml")))
            environment.getPropertySources().addLast(source);
        for (var source : yaml.load("defaults", new ClassPathResource("application-default.yml")))
            environment.getPropertySources().addLast(source);
        new LogbackLoggingSystemProperties(
                        environment,
                        (key, value) -> {
                            rememberProperty(key);
                            if (value != null) System.setProperty(key, value);
                        })
                .apply(LogFile.get(environment));
        loggingSystem = new LogbackLoggingSystem(getClass().getClassLoader());
        loggingSystem.beforeInitialize();
        loggingSystem.initialize(
                new LoggingInitializationContext(environment),
                "classpath:logback-spring.xml",
                LogFile.get(environment));
    }

    private LoggingEvent event(Level level, String message) {
        return event(level, message, Map.of());
    }

    private LoggingEvent event(Level level, String message, Map<String, String> mdc) {
        var event = new LoggingEvent();
        event.setLoggerContextRemoteView(loggerContext.getLoggerContextRemoteView());
        event.setMDCPropertyMap(mdc);
        event.setTimeStamp(Instant.parse("2026-10-10T03:53:55.834Z").toEpochMilli());
        event.setLevel(level);
        event.setLoggerName("com.gaiys.proto.ProtoBridgeApplication");
        event.setThreadName("main");
        event.setMessage(message);
        return event;
    }

    @Configuration(proxyBeanMethods = false)
    static class ConsoleProbe {}
}
