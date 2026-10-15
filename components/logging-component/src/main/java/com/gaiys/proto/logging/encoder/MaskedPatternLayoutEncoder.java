package com.gaiys.proto.logging.encoder;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.pattern.EnsureExceptionHandling;
import ch.qos.logback.classic.pattern.KeyValuePairConverter;
import ch.qos.logback.classic.pattern.MaskedKeyValuePairConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.pattern.CompositeConverter;
import ch.qos.logback.core.pattern.Converter;
import ch.qos.logback.core.pattern.PatternLayoutEncoderBase;
import com.gaiys.proto.logging.masker.SensitiveValueMasker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * Text console encoder using Logback's public pattern extension points.
 *
 * <p>Logback creates one encoder per console appender. Only the rendered event is sanitized, so the
 * original event remains available to the independent JSON file and log-store appenders. Standard
 * Spring Boot patterns and user pattern overrides use the same masking boundary.
 *
 * @see SensitiveValueMasker
 */
public final class MaskedPatternLayoutEncoder extends PatternLayoutEncoderBase<ILoggingEvent> {
    private boolean appendKeyValuePairs;
    private final KeyValuePairConverter keyValuePairConverter = new KeyValuePairConverter();

    @Override
    public void start() {
        var patternLayout = new PatternLayout();
        patternLayout.setContext(getContext());
        patternLayout.setPattern(getPattern());
        patternLayout.setOutputPatternAsHeader(isOutputPatternAsHeader());
        patternLayout.setPostCompileProcessor(
                (context, head) -> {
                    new EnsureExceptionHandling().process(context, head);
                    // Boot's default pattern has only a message; retain structured audit details.
                    appendKeyValuePairs = !hasKeyValuePairConverter(head);
                });
        patternLayout.start();
        layout = patternLayout;
        keyValuePairConverter.setContext(getContext());
        keyValuePairConverter.start();
        super.start();
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        var safeEvent = new MaskedLoggingEvent(event);
        if (appendKeyValuePairs) {
            safeEvent.appendKeyValuePairs(keyValuePairConverter.convert(safeEvent));
        }
        return super.encode(safeEvent);
    }

    private static boolean hasKeyValuePairConverter(Converter<ILoggingEvent> head) {
        for (var converter = head; converter != null; converter = converter.getNext()) {
            if (converter instanceof KeyValuePairConverter
                    || converter instanceof MaskedKeyValuePairConverter) return true;
            if (converter instanceof CompositeConverter<ILoggingEvent> composite
                    && hasKeyValuePairConverter(composite.getChildConverter())) return true;
        }
        return false;
    }

    /**
     * Holds one safe snapshot used while the text console encoder formats an event.
     *
     * <p>Pattern aliases read masked arguments, MDC, structured pairs and exception types from this
     * snapshot. The original event remains owned by Logback and available to independent appenders;
     * this short-lived adapter does not change its content or lifecycle.
     *
     * @see MaskedPatternLayoutEncoder
     */
    private static final class MaskedLoggingEvent implements ILoggingEvent {
        private final ILoggingEvent source;
        private final Map<String, String> mdc = new LinkedHashMap<>();
        private final List<KeyValuePair> keyValuePairs = new ArrayList<>();
        private final IThrowableProxy throwable;
        private final List<Marker> markers = new ArrayList<>();
        private String message;

        MaskedLoggingEvent(ILoggingEvent source) {
            this.source = source;
            message = redact(source.getFormattedMessage());
            source.getMDCPropertyMap()
                    .forEach((key, value) -> mdc.put(key, String.valueOf(fieldValue(key, value))));
            if (source.getKeyValuePairs() != null) {
                for (var pair : source.getKeyValuePairs()) {
                    keyValuePairs.add(new KeyValuePair(pair.key, fieldValue(pair.key, pair.value)));
                }
            }
            if (source.getMarkerList() != null) {
                var visited = Collections.newSetFromMap(new IdentityHashMap<Marker, Boolean>());
                for (var marker : source.getMarkerList()) {
                    var safe = safeMarker(marker, visited);
                    if (safe != null) markers.add(safe);
                }
            }
            throwable =
                    safeThrowable(
                            source.getThrowableProxy(),
                            Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        void appendKeyValuePairs(String pairs) {
            if (pairs != null && !pairs.isEmpty()) {
                message = (message == null ? "" : message + " ") + pairs;
            }
        }

        private static Object fieldValue(String field, Object value) {
            return fieldValue(field, value, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        }

        private static Object fieldValue(
                String field, Object value, Set<Object> visited, int depth) {
            if (SensitiveValueMasker.isSensitiveField(field)) return "[REDACTED]";
            if (value == null || value instanceof Number || value instanceof Boolean) return value;
            if (value instanceof CharSequence
                    || value instanceof Enum<?>
                    || value instanceof Character) return redact(value.toString());
            // JSON masks nested fields too; snapshot containers before their text representation is
            // made.
            if (depth >= 32 || !visited.add(value)) return "[REDACTED]";
            try {
                if (value instanceof Map<?, ?> map) {
                    var safe = new LinkedHashMap<String, Object>();
                    map.forEach(
                            (key, item) -> {
                                String name = String.valueOf(key);
                                safe.put(redact(name), fieldValue(name, item, visited, depth + 1));
                            });
                    return safe;
                }
                if (value instanceof Iterable<?> items) {
                    var safe = new ArrayList<>();
                    for (var item : items) safe.add(fieldValue(null, item, visited, depth + 1));
                    return safe;
                }
                if (value instanceof Object[] items) {
                    var safe = new ArrayList<>();
                    for (var item : items) safe.add(fieldValue(null, item, visited, depth + 1));
                    return safe;
                }
                // Arbitrary objects may expose unlabelled credentials from their toString().
                return "[REDACTED]";
            } finally {
                visited.remove(value);
            }
        }

        private static Marker safeMarker(Marker source, Set<Marker> visited) {
            if (source == null || visited.size() >= 32 || !visited.add(source)) return null;
            var marker = MarkerFactory.getDetachedMarker(redact(source.getName()));
            var references = source.iterator();
            while (references.hasNext()) {
                var safe = safeMarker(references.next(), visited);
                if (safe != null) marker.add(safe);
            }
            return marker;
        }

        private static String redact(String text) {
            return text == null ? null : SensitiveValueMasker.redact(text);
        }

        private static IThrowableProxy safeThrowable(
                IThrowableProxy source, Set<IThrowableProxy> visited) {
            if (source == null || visited.size() >= 32 || !visited.add(source)) return null;
            var suppressed = new ArrayList<IThrowableProxy>();
            if (source.getSuppressed() != null) {
                for (var item : source.getSuppressed()) {
                    var safe = safeThrowable(item, visited);
                    if (safe != null) suppressed.add(safe);
                }
            }
            // Keep exception types and cause/suppression relationships, matching the JSON policy.
            return new SafeThrowableProxy(
                    source.getClassName(),
                    safeThrowable(source.getCause(), visited),
                    suppressed.toArray(IThrowableProxy[]::new));
        }

        private record SafeThrowableProxy(
                String className, IThrowableProxy cause, IThrowableProxy[] suppressed)
                implements IThrowableProxy {
            @Override
            public String getMessage() {
                return null;
            }

            @Override
            public String getClassName() {
                return className;
            }

            @Override
            public StackTraceElementProxy[] getStackTraceElementProxyArray() {
                return new StackTraceElementProxy[0];
            }

            @Override
            public int getCommonFrames() {
                return 0;
            }

            @Override
            public IThrowableProxy getCause() {
                return cause;
            }

            @Override
            public IThrowableProxy[] getSuppressed() {
                return suppressed;
            }

            @Override
            public boolean isCyclic() {
                return false;
            }
        }

        @Override
        public String getThreadName() {
            return redact(source.getThreadName());
        }

        @Override
        public Level getLevel() {
            return source.getLevel();
        }

        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public Object[] getArgumentArray() {
            return null;
        }

        @Override
        public String getFormattedMessage() {
            return message;
        }

        @Override
        public String getLoggerName() {
            return redact(source.getLoggerName());
        }

        @Override
        public LoggerContextVO getLoggerContextVO() {
            return source.getLoggerContextVO();
        }

        @Override
        public IThrowableProxy getThrowableProxy() {
            return throwable;
        }

        @Override
        public StackTraceElement[] getCallerData() {
            return source.getCallerData();
        }

        @Override
        public boolean hasCallerData() {
            return source.hasCallerData();
        }

        @Override
        public List<Marker> getMarkerList() {
            return markers;
        }

        @Override
        public Map<String, String> getMDCPropertyMap() {
            return mdc;
        }

        @Deprecated
        @Override
        public Map<String, String> getMdc() {
            return mdc;
        }

        @Override
        public long getTimeStamp() {
            return source.getTimeStamp();
        }

        @Override
        public int getNanoseconds() {
            return source.getNanoseconds();
        }

        @Override
        public long getSequenceNumber() {
            return source.getSequenceNumber();
        }

        @Override
        public List<KeyValuePair> getKeyValuePairs() {
            return keyValuePairs;
        }

        @Override
        public void prepareForDeferredProcessing() {
            // All sensitive values are already snapshots; source metadata is prepared by
            // AsyncAppender.
        }
    }
}
