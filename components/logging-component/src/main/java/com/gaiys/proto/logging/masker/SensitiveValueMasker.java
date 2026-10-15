package com.gaiys.proto.logging.masker;

import com.fasterxml.jackson.core.JsonStreamContext;
import com.gaiys.proto.logging.encoder.MaskedPatternLayoutEncoder;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.logstash.logback.mask.ValueMasker;

/**
 * Applies the shared sensitive-field and text redaction policy to JSON log values.
 *
 * <p>Logback creates the stateless JSON extension; the text console encoder also uses its helpers.
 * Formatting and log delivery remain with the encoders and sinks. Exception values retain bounded
 * type names only, so messages and frames cannot expose SQL, credentials or file paths.
 *
 * @see MaskedPatternLayoutEncoder
 */
public class SensitiveValueMasker implements ValueMasker {
    private static final Pattern FIELD =
            Pattern.compile(
                    "(?i).*password.*|.*token.*|access.?key|preview.?key|api.?key|private.?key|client.?secret|verify.?code|encrypt.?id|authorization|cookie|salt");
    private static final Pattern THROWABLE_TYPE =
            Pattern.compile(
                    "(?m)^\\h*(?:Caused by: |Suppressed: )?"
                            + "((?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*)(?=:|\\r?$)");
    private static final Pattern PAIR =
            Pattern.compile(
                    "(?i)(password|token|access[_-]?key|preview[_-]?key|api[_-]?key|private[_-]?key|client[_-]?secret|verify[_-]?code|encrypt[_-]?id|authorization|cookie)\"?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)");
    // 历史日志中仍可能出现旧能力链接；两种前缀都必须隐藏密钥，与业务路由是否开放无关。
    private static final Pattern CAPABILITY =
            Pattern.compile(
                    "(/(?:api/)?(?:prototype-hub|protohub)/(?:runtime|public/shares)/)[^\\s\"']+");
    private static final Pattern FILE =
            Pattern.compile("(?<![\\w:/])(?:[A-Za-z]:\\\\|/)[^\\s\"',;)}]+");

    @Override
    public Object mask(JsonStreamContext context, Object value) {
        boolean stackTrace = false;
        for (JsonStreamContext current = context; current != null; current = current.getParent()) {
            if (isSensitiveField(current.getCurrentName())) return "[REDACTED]";
            stackTrace |= "stack_trace".equals(current.getCurrentName());
        }
        if (!(value instanceof String text)) return null;
        if (stackTrace) {
            // Keep only exception class names; messages, SQL, paths and stack frames stay private.
            String types =
                    THROWABLE_TYPE
                            .matcher(text)
                            .results()
                            .map(match -> match.group(1))
                            .distinct()
                            .limit(32)
                            .collect(Collectors.joining("\n"));
            return types.isEmpty() ? "[REDACTED]" : types;
        }
        return redact(text);
    }

    /** Shares JSON field-name masking with the text console encoder. */
    public static boolean isSensitiveField(String name) {
        return name != null && FIELD.matcher(name).matches();
    }

    public static String redact(String text) {
        if (text.matches(
                "(?is).*(?:select\\s+.+\\s+from|insert\\s+into|update\\s+.+\\s+set|delete\\s+from).*"))
            return "[SQL REDACTED]";
        String safe = text.replaceAll("(?i)(Bearer|ApiKey|Basic) [^\\s,;]+", "$1 [REDACTED]");
        safe = PAIR.matcher(safe).replaceAll("$1=[REDACTED]");
        safe = CAPABILITY.matcher(safe).replaceAll("$1[REDACTED]");
        return FILE.matcher(safe).replaceAll("[PATH]");
    }
}
