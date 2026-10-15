package com.gaiys.proto.logging.capture;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.gaiys.proto.logging.document.LogDocument;
import com.gaiys.proto.logging.masker.SensitiveValueMasker;
import com.gaiys.proto.logging.properties.LogStorageProperties;
import com.gaiys.proto.logging.store.LogStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Attaches a system-log appender to the root logger for masked recent-log storage.
 *
 * <p>Spring registers and removes the appender. Sink and client loggers are excluded to prevent
 * recursive capture; this adapter does not change the configured console or file appenders.
 */
@Component
public class SystemLogCapture {
    private final LogStore store;
    private final LogStorageProperties settings;
    private final String server;
    private AppenderBase<ILoggingEvent> appender;

    public SystemLogCapture(
            LogStore store,
            LogStorageProperties settings,
            @Value("${spring.application.name}:${server.port}") String server) {
        this.store = store;
        this.settings = settings;
        this.server = server;
    }

    @PostConstruct
    public void start() {
        if (!settings.isSystemEnabled()) return;
        if (!Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")
                .contains(settings.getSystemMinLevel()))
            throw new IllegalArgumentException("validation.log_settings_invalid");
        Level minimum = Level.toLevel(settings.getSystemMinLevel());
        appender =
                new AppenderBase<>() {
                    @Override
                    protected void append(ILoggingEvent event) {
                        if (!event.getLevel().isGreaterOrEqual(minimum)
                                || event.getLoggerName().startsWith("proto.logging")
                                || event.getLoggerName().startsWith("proto.audit")
                                || event.getLoggerName().startsWith("proto.access")
                                || event.getLoggerName().startsWith("co.elastic.clients")
                                || event.getLoggerName().startsWith("org.elasticsearch.client"))
                            return;
                        String message = SensitiveValueMasker.redact(event.getFormattedMessage());
                        if (message.length() > 8192) message = message.substring(0, 8192);
                        store.offer(
                                LogDocument.builder()
                                        .kind("system")
                                        .createTime(event.getTimeStamp())
                                        .level(event.getLevel().toString())
                                        .message(message)
                                        .name(event.getLoggerName())
                                        .threadName(event.getThreadName())
                                        .server(server)
                                        .exceptionStack(
                                                event.getThrowableProxy() == null
                                                        ? null
                                                        : "[REDACTED]")
                                        .build());
                    }
                };
        appender.setName("PROTO_LOG_STORE");
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(appender);
    }

    @PreDestroy
    public void close() {
        // Detach first so concurrent logger calls cannot reach a stopped appender.
        if (appender != null) {
            ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                    .detachAppender(appender);
            appender.stop();
        }
    }
}
