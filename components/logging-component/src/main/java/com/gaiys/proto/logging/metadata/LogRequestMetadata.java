package com.gaiys.proto.logging.metadata;

import com.gaiys.proto.logging.masker.SensitiveValueMasker;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

/**
 * Captures bounded request headers and query parameters for log details.
 *
 * <p>Headers use an explicit allowlist; request bodies, cookies and authentication headers are not
 * captured. Parameter names and string values are redacted before both log sinks see them.
 *
 * <p>Percent normalization inspects at most four candidate encoding layers and preserves literal
 * plus signs. Invalid percent/UTF-8 inside a candidate or excessive nesting is redacted; ordinary
 * percent text and benign encoded values keep their original display representation. JSON limits
 * apply to the final redacted value.
 */
public final class LogRequestMetadata {
    private static final int MAX_BYTES = 4096,
            MAX_FIELDS = 32,
            MAX_VALUES = 4,
            MAX_ENCODING_DEPTH = 4;
    private static final Set<String> HEADERS =
            Set.of("accept", "content-type", "content-length", "user-agent", "host");
    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final Pattern URL_SHAPE = Pattern.compile("(?i)https?://");
    private static final Pattern URL_QUERY = Pattern.compile("(?i)(https?://[^\\s?#]+)[?#][^\\s]*");

    private LogRequestMetadata() {}

    public static Map<String, List<String>> headers(HttpHeaders headers) {
        return capture(headers, true);
    }

    public static Map<String, List<String>> parameters(MultiValueMap<String, String> parameters) {
        return capture(parameters, false);
    }

    private static Map<String, List<String>> capture(
            Map<String, List<String>> source, boolean header) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        int remaining = MAX_BYTES - 2; // Include the surrounding JSON object braces.
        for (var entry : source.entrySet()) {
            String name = entry.getKey();
            if (name == null || (header && !HEADERS.contains(name.toLowerCase(Locale.ROOT))))
                continue;
            if (result.size() == MAX_FIELDS || remaining <= 0) break;
            int fieldOverhead =
                    5
                            + (result.isEmpty()
                                    ? 0
                                    : 1); // Quoted key, colon, array brackets and separator.
            if (remaining < fieldOverhead) break;
            String key =
                    truncate(
                            header ? name.toLowerCase(Locale.ROOT) : name,
                            Math.min(128, remaining - fieldOverhead));
            if (key.isEmpty()) continue;
            remaining -= jsonBytes(key) + fieldOverhead;
            List<String> values = new ArrayList<>();
            if (entry.getValue() != null)
                for (String value : entry.getValue()) {
                    int valueOverhead = 2 + (values.isEmpty() ? 0 : 1);
                    if (values.size() == MAX_VALUES || remaining < valueOverhead) break;
                    String safe =
                            sensitive(name) ? "[REDACTED]" : safeValue(value == null ? "" : value);
                    safe = truncate(safe, Math.min(512, remaining - valueOverhead));
                    remaining -= jsonBytes(safe) + valueOverhead;
                    values.add(safe);
                }
            result.put(key, List.copyOf(values));
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean sensitive(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.endsWith("key")
                || normalized.equals("salt")
                || normalized.equals("verifycode")
                || normalized.equals("encryptid");
    }

    private static String safeValue(String value) {
        String current = value;
        boolean changed = false;
        for (int depth = 0; ; depth++) {
            // Inspect each encoding layer before keeping the original display value. A nested URL
            // query/fragment can contain credentials that ordinary key=value masking misses.
            String safe =
                    URL_QUERY
                            .matcher(SensitiveValueMasker.redact(current))
                            .replaceAll("$1?[REDACTED]");
            // Redacting one fragment does not make its encoded siblings safe. Continue with
            // the accumulated safe text until every candidate encoding layer is inspected.
            changed |= !safe.equals(current);
            current = safe;
            if (current.indexOf('%') < 0) return changed ? current : value;
            // Percent is also ordinary display/query text. Decode only with a complete %HH
            // candidate or a URL shape; after decoding, a literal percent does not add a layer.
            if (!PERCENT_ESCAPE.matcher(current).find()
                    && (depth > 0 || !URL_SHAPE.matcher(current).find()))
                return changed ? current : value;
            if (depth == MAX_ENCODING_DEPTH) return "[REDACTED]";
            try {
                current = decodePercent(current);
            } catch (IllegalArgumentException | CharacterCodingException error) {
                return "[REDACTED]";
            }
        }
    }

    private static String decodePercent(String value) throws CharacterCodingException {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length(); ) {
            if (value.charAt(offset) != '%') {
                // Plus is literal metadata text; form-style plus-to-space decoding is not used.
                decoded.append(value.charAt(offset++));
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            do {
                if (offset + 2 >= value.length()) throw new IllegalArgumentException("percent");
                int high = hexDigit(value.charAt(offset + 1));
                int low = hexDigit(value.charAt(offset + 2));
                if (high < 0 || low < 0) throw new IllegalArgumentException("percent");
                bytes.write((high << 4) | low);
                offset += 3;
            } while (offset < value.length() && value.charAt(offset) == '%');
            decoded.append(
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes.toByteArray())));
        }
        return decoded.toString();
    }

    private static int hexDigit(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        return -1;
    }

    private static int jsonBytes(String value) {
        int bytes = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            bytes += jsonWidth(codePoint);
            offset += Character.charCount(codePoint);
        }
        return bytes;
    }

    private static int jsonWidth(int codePoint) {
        if (codePoint < 0x20 || (codePoint <= 0xffff && Character.isSurrogate((char) codePoint)))
            return 6;
        if (codePoint == '"' || codePoint == '\\') return 2;
        // Jackson's UTF-8 generator can emit supplementary characters as two escaped surrogates.
        return codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 12;
    }

    private static String truncate(String value, int maximumBytes) {
        int offset = 0, size = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            int width = jsonWidth(codePoint);
            if (size + width > maximumBytes) break;
            size += width;
            offset += Character.charCount(codePoint);
        }
        return value.substring(0, offset);
    }
}
