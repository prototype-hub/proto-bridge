package com.gaiys.proto.prototypehub.service.validation.dtcg;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Normative 2025.10 value shapes; reference resolution is performed before these checks. */
public final class DtcgTypes {
    public static final Set<String> TYPES =
            Set.of(
                    "color",
                    "dimension",
                    "fontFamily",
                    "fontWeight",
                    "duration",
                    "cubicBezier",
                    "number",
                    "strokeStyle",
                    "border",
                    "transition",
                    "shadow",
                    "gradient",
                    "typography");
    private static final Set<String> WEIGHTS =
            Set.of(
                    "thin",
                    "hairline",
                    "extra-light",
                    "ultra-light",
                    "light",
                    "normal",
                    "regular",
                    "book",
                    "medium",
                    "semi-bold",
                    "demi-bold",
                    "bold",
                    "extra-bold",
                    "ultra-bold",
                    "black",
                    "heavy",
                    "extra-black",
                    "ultra-black");
    private static final Set<String> STROKES =
            Set.of("solid", "dashed", "dotted", "double", "groove", "ridge", "outset", "inset");
    private static final Set<String> RGB =
            Set.of(
                    "srgb",
                    "srgb-linear",
                    "display-p3",
                    "a98-rgb",
                    "prophoto-rgb",
                    "rec2020",
                    "xyz-d65",
                    "xyz-d50");

    private DtcgTypes() {}

    public static boolean valid(String type, JsonNode value) {
        if (type == null || value == null || value.isNull()) return false;
        return switch (type) {
            case "number" -> value.isNumber();
            case "dimension" -> measured(value, Set.of("px", "rem"));
            case "duration" -> measured(value, Set.of("ms", "s"));
            case "fontFamily" -> value.isTextual() || value.isArray() && every(value, "string");
            case "fontWeight" ->
                    between(value, 1, 1000, false)
                            || value.isTextual() && WEIGHTS.contains(value.textValue());
            case "cubicBezier" ->
                    value.isArray()
                            && value.size() == 4
                            && every(value, "number")
                            && between(value.get(0), 0, 1, false)
                            && between(value.get(2), 0, 1, false);
            case "color" -> color(value);
            case "strokeStyle" ->
                    value.isTextual()
                            ? STROKES.contains(value.textValue())
                            : exact(value, Set.of("dashArray", "lineCap"), Set.of())
                                    && value.path("dashArray").isArray()
                                    && every(value.get("dashArray"), "dimension")
                                    && Set.of("round", "butt", "square")
                                            .contains(value.path("lineCap").asText());
            case "border" ->
                    composite(
                            value,
                            Map.of("color", "color", "width", "dimension", "style", "strokeStyle"),
                            Set.of());
            case "transition" ->
                    composite(
                            value,
                            Map.of(
                                    "duration",
                                    "duration",
                                    "delay",
                                    "duration",
                                    "timingFunction",
                                    "cubicBezier"),
                            Set.of());
            case "shadow" ->
                    value.isArray() ? every(value, "shadowObject") : valid("shadowObject", value);
            case "shadowObject" ->
                    composite(
                                    value,
                                    Map.of(
                                            "color",
                                            "color",
                                            "offsetX",
                                            "dimension",
                                            "offsetY",
                                            "dimension",
                                            "blur",
                                            "dimension",
                                            "spread",
                                            "dimension"),
                                    Set.of("inset"))
                            && (!value.has("inset") || value.get("inset").isBoolean());
            case "gradient" -> value.isArray() && every(value, "gradientStop");
            case "gradientStop" ->
                    composite(value, Map.of("color", "color", "position", "number"), Set.of());
            case "typography" ->
                    composite(
                            value,
                            Map.of(
                                    "fontFamily",
                                    "fontFamily",
                                    "fontSize",
                                    "dimension",
                                    "fontWeight",
                                    "fontWeight",
                                    "letterSpacing",
                                    "dimension",
                                    "lineHeight",
                                    "number"),
                            Set.of());
            case "string" -> value.isTextual();
            default -> false;
        };
    }

    public static String childType(String type, String key, boolean array) {
        if (type == null) return null;
        if (array)
            return switch (type) {
                case "shadow" -> "shadow";
                case "gradient" -> "gradient";
                case "fontFamily" -> "fontFamily";
                case "cubicBezier", "components" -> "number";
                case "dashArray" -> "dimension";
                default -> null;
            };
        return switch (key) {
            case "color" -> Set.of("border", "shadow", "gradient").contains(type) ? "color" : null;
            case "width", "offsetX", "offsetY", "blur", "spread", "fontSize", "letterSpacing" ->
                    "dimension";
            case "style" -> "strokeStyle";
            case "duration", "delay" -> "duration";
            case "timingFunction" -> "cubicBezier";
            case "fontFamily" -> "fontFamily";
            case "fontWeight" -> "fontWeight";
            case "position", "lineHeight", "value", "alpha" -> "number";
            case "dashArray", "components" -> key;
            default -> null;
        };
    }

    private static boolean measured(JsonNode v, Set<String> units) {
        return exact(v, Set.of("value", "unit"), Set.of())
                && v.get("value").isNumber()
                && v.get("unit").isTextual()
                && units.contains(v.get("unit").textValue());
    }

    private static boolean composite(JsonNode v, Map<String, String> fields, Set<String> optional) {
        if (!exact(v, fields.keySet(), optional)) return false;
        return fields.entrySet().stream()
                .allMatch(field -> valid(field.getValue(), v.get(field.getKey())));
    }

    private static boolean every(JsonNode array, String type) {
        for (JsonNode item : array) if (!valid(type, item)) return false;
        return true;
    }

    private static boolean exact(JsonNode v, Set<String> required, Set<String> optional) {
        if (!v.isObject()) return false;
        for (String key : required) if (!v.has(key)) return false;
        Iterator<String> keys = v.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!required.contains(key) && !optional.contains(key)) return false;
        }
        return true;
    }

    private static boolean color(JsonNode v) {
        if (!exact(v, Set.of("colorSpace", "components"), Set.of("alpha", "hex"))
                || !v.get("colorSpace").isTextual()) return false;
        String space = v.get("colorSpace").textValue();
        JsonNode components = v.get("components");
        if (!components.isArray() || components.size() != 3) return false;
        if (v.has("alpha") && !between(v.get("alpha"), 0, 1, false)) return false;
        if (v.has("hex")
                && (!v.get("hex").isTextual()
                        || !v.get("hex").textValue().matches("#[0-9a-fA-F]{6}"))) return false;
        if (!RGB.contains(space)
                && !Set.of("hsl", "hwb", "lab", "lch", "oklab", "oklch").contains(space))
            return false;
        for (int i = 0; i < 3; i++) {
            JsonNode component = components.get(i);
            if (component.isTextual() && "none".equals(component.textValue())) continue;
            if (!component.isNumber()) return false;
            if (RGB.contains(space) && !between(component, 0, 1, false)) return false;
            if (Set.of("hsl", "hwb").contains(space)
                    && !between(component, 0, i == 0 ? 360 : 100, i == 0)) return false;
            if (Set.of("lab", "lch", "oklab", "oklch").contains(space)
                    && i == 0
                    && !between(component, 0, space.startsWith("ok") ? 1 : 100, false))
                return false;
            if (Set.of("lch", "oklch").contains(space)
                    && (i == 1 && component.decimalValue().signum() < 0
                            || i == 2 && !between(component, 0, 360, true))) return false;
        }
        return true;
    }

    private static boolean between(JsonNode v, int low, int high, boolean exclusiveHigh) {
        if (v == null || !v.isNumber()) return false;
        BigDecimal value = v.decimalValue();
        return value.compareTo(BigDecimal.valueOf(low)) >= 0
                && (exclusiveHigh
                        ? value.compareTo(BigDecimal.valueOf(high)) < 0
                        : value.compareTo(BigDecimal.valueOf(high)) <= 0);
    }
}
