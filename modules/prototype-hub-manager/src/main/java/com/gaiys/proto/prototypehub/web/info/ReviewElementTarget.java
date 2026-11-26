package com.gaiys.proto.prototypehub.web.info;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 用户显式选取的固定页DOM位置快照；不推断业务语义，不作为页面身份或授权依据。 */
@Schema(description = "不可变元素位置元数据，跨frame只记录包内相对路径与唯一DOM标识")
public record ReviewElementTarget(
        String space,
        List<Frame> frames,
        String elementId,
        String tagName,
        String textHint,
        int viewportWidth,
        int viewportHeight) {
    private static final Set<String> FIELDS =
            Set.of(
                    "space",
                    "frames",
                    "elementId",
                    "tagName",
                    "textHint",
                    "viewportWidth",
                    "viewportHeight");
    private static final Set<String> FRAME_FIELDS = Set.of("elementId", "resourcePath");

    public ReviewElementTarget {
        if (!"frame-element-v1".equals(space)
                || frames == null
                || frames.size() > 4
                || frames.stream().anyMatch(java.util.Objects::isNull)
                || !validId(elementId)
                || tagName == null
                || !tagName.matches("[a-z][a-z0-9]{0,31}")
                || textHint == null
                || textHint.length() > 160
                || textHint.codePoints().anyMatch(c -> c < 32 || (c >= 0xD800 && c <= 0xDFFF))
                || viewportWidth < 1
                || viewportWidth > 16384
                || viewportHeight < 1
                || viewportHeight > 16384) throw invalid();
        // Frame records and strings are immutable; copy the caller's list before it can change.
        frames = List.copyOf(frames);
    }

    /** 单层frame身份；路径仅描述位置，服务端不会访问或请求该路径。 */
    public record Frame(String elementId, String resourcePath) {
        public Frame {
            if (!validId(elementId) || !validPath(resourcePath)) throw invalid();
        }
    }

    /** 严格解析与持久化JsonCodec共用，不接受未知字段或整数默认转换。 */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ReviewElementTarget from(JsonNode value) {
        exactFields(value, FIELDS);
        JsonNode frames = value.get("frames");
        if (!frames.isArray() || frames.size() > 4) throw invalid();
        var path = new ArrayList<Frame>(frames.size());
        for (JsonNode frame : frames) {
            exactFields(frame, FRAME_FIELDS);
            path.add(new Frame(text(frame, "elementId"), text(frame, "resourcePath")));
        }
        return new ReviewElementTarget(
                text(value, "space"),
                path,
                text(value, "elementId"),
                text(value, "tagName"),
                text(value, "textHint"),
                integer(value, "viewportWidth"),
                integer(value, "viewportHeight"));
    }

    /** 固定字段顺序与长度前缀让嵌套JSON重排等价，提示文字中的分隔符不产生歧义。 */
    public String canonical() {
        var value = new StringBuilder("element-target-v1;");
        field(value, space);
        value.append(frames.size()).append(';');
        for (Frame frame : frames) {
            field(value, frame.elementId());
            field(value, frame.resourcePath());
        }
        field(value, elementId);
        field(value, tagName);
        field(value, textHint);
        return value.append(viewportWidth).append(';').append(viewportHeight).toString();
    }

    private static void field(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static void exactFields(JsonNode value, Set<String> fields) {
        if (!value.isObject() || value.size() != fields.size()) throw invalid();
        var names = value.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) throw invalid();
    }

    private static String text(JsonNode value, String name) {
        if (!value.path(name).isTextual()) throw invalid();
        return value.get(name).textValue();
    }

    private static int integer(JsonNode value, String name) {
        if (!value.path(name).isIntegralNumber() || !value.get(name).canConvertToInt())
            throw invalid();
        return value.get(name).intValue();
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}");
    }

    private static boolean validPath(String value) {
        if (value == null || value.isEmpty() || value.length() > 4096) return false;
        String first = value.split("/", 2)[0];
        if (first.indexOf(':') >= 0) return false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c > 126 || c == '?' || c == '#' || c == '\\' || c <= 32) return false;
            if (c == '%'
                    && (index + 2 >= value.length()
                            || hex(value.charAt(++index)) < 0
                            || hex(value.charAt(++index)) < 0)) return false;
        }
        for (String part : value.split("/", -1)) {
            String decoded = decodeSegment(part);
            if (decoded == null) return false;
            if (part.isEmpty()
                    || decoded.equals(".")
                    || decoded.equals("..")
                    || decoded.chars().anyMatch(c -> c == '/' || c == '\\' || c < 32 || c == 127))
                return false;
        }
        return true;
    }

    private static int hex(char value) {
        return value <= 127 ? Character.digit(value, 16) : -1;
    }

    private static String decodeSegment(String value) {
        var bytes = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == '%') bytes.write(hex(value.charAt(++index)) * 16 + hex(value.charAt(++index)));
            else bytes.write(c);
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException invalidEncoding) {
            return null;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid element target");
    }
}
