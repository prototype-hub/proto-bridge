package com.gaiys.proto.prototypehub.service.validation;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.web.response.DesignTokenDocument;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesignTokenParserTest {
    private final DesignTokenParser parser = new DesignTokenParser();
    private static final String COLOR = "{\"colorSpace\":\"srgb\",\"components\":[0.1,0.4,1]}";
    private static final String DIMENSION = "{\"value\":16,\"unit\":\"px\"}";
    private static final String SHADOW =
            "{\"color\":"
                    + COLOR
                    + ",\"offsetX\":"
                    + DIMENSION
                    + ",\"offsetY\":"
                    + DIMENSION
                    + ",\"blur\":"
                    + DIMENSION
                    + ",\"spread\":"
                    + DIMENSION
                    + ",\"inset\":true}";

    private DesignTokenDocument parse(String text) {
        return parser.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private DesignTokenDocument.TokenView token(DesignTokenDocument document, String... path) {
        return document.getTokens().stream()
                .filter(token -> token.getPath().equals(List.of(path)))
                .findFirst()
                .orElseThrow();
    }

    private static String declaration(String type, String value) {
        return "{\"$type\":\"" + type + "\",\"$value\":" + value + "}";
    }

    @Test
    void acceptsEveryStandardTypeAndPreservesOpaqueExtensions() {
        Map<String, String> values =
                Map.ofEntries(
                        Map.entry("color", COLOR),
                        Map.entry("dimension", DIMENSION),
                        Map.entry("fontFamily", "[\"Inter\",\"sans-serif\"]"),
                        Map.entry("fontWeight", "\"semi-bold\""),
                        Map.entry("duration", "{\"value\":0.2,\"unit\":\"s\"}"),
                        Map.entry("cubicBezier", "[0.2,-1,0.8,2]"),
                        Map.entry("number", "-1.25"),
                        Map.entry(
                                "strokeStyle",
                                "{\"dashArray\":[" + DIMENSION + "],\"lineCap\":\"round\"}"),
                        Map.entry(
                                "border",
                                "{\"color\":"
                                        + COLOR
                                        + ",\"width\":"
                                        + DIMENSION
                                        + ",\"style\":\"solid\"}"),
                        Map.entry(
                                "transition",
                                "{\"duration\":{\"value\":1,\"unit\":\"ms\"},\"delay\":{\"value\":0,\"unit\":\"ms\"},\"timingFunction\":[0,0,1,1]}"),
                        Map.entry("shadow", "[" + SHADOW + "]"),
                        Map.entry(
                                "gradient",
                                "[{\"color\":"
                                        + COLOR
                                        + ",\"position\":-2},{\"color\":"
                                        + COLOR
                                        + ",\"position\":42}]"),
                        Map.entry(
                                "typography",
                                "{\"fontFamily\":\"Inter\",\"fontSize\":"
                                        + DIMENSION
                                        + ",\"fontWeight\":400,\"letterSpacing\":"
                                        + DIMENSION
                                        + ",\"lineHeight\":1.4}"));
        StringBuilder input =
                new StringBuilder(
                        "{\"$extensions\":{\"org.example\":{\"__proto__\":\"<script>\"}},");
        values.forEach(
                (type, value) ->
                        input.append('"')
                                .append(type)
                                .append("\":")
                                .append(declaration(type, value))
                                .append(','));
        input.append(
                "\"metadata\":{\"$type\":\"number\",\"$value\":1,\"$extensions\":{\"org.example\":{\"constructor\":\"<img>\"}}}}\n");
        DesignTokenDocument document = parse(input.toString());
        assertTrue(document.isValid(), document.getIssues().toString());
        assertEquals(14, document.getTokenCount());
        assertEquals(
                "<img>",
                token(document, "metadata")
                        .getExtensions()
                        .path("org.example")
                        .path("constructor")
                        .asText());
        assertEquals(
                0,
                token(document, "gradient").getResolvedValue().get(0).path("position").intValue());
        assertEquals(
                1,
                token(document, "gradient").getResolvedValue().get(1).path("position").intValue());
        assertEquals(
                -2,
                token(document, "gradient").getDeclaredValue().get(0).path("position").intValue());
    }

    @Test
    void acceptsEveryColorSpaceNoneAndUnboundedLabCoordinates() {
        for (String space :
                List.of(
                        "srgb",
                        "srgb-linear",
                        "display-p3",
                        "a98-rgb",
                        "prophoto-rgb",
                        "rec2020",
                        "xyz-d65",
                        "xyz-d50",
                        "hsl",
                        "hwb",
                        "lab",
                        "lch",
                        "oklab",
                        "oklch")) {
            DesignTokenDocument document =
                    parse(
                            "{\"color\":"
                                    + declaration(
                                            "color",
                                            "{\"colorSpace\":\""
                                                    + space
                                                    + "\",\"components\":[\"none\",0,0],\"alpha\":0.5,\"hex\":\"#Aa0011\"}")
                                    + "}");
            assertTrue(document.isValid(), space + document.getIssues());
        }
        assertTrue(
                parse(
                                "{\"color\":"
                                        + declaration(
                                                "color",
                                                "{\"colorSpace\":\"lab\",\"components\":[100,-99999,99999]}")
                                        + "}")
                        .isValid());
        assertFalse(
                parse(
                                "{\"color\":"
                                        + declaration(
                                                "color",
                                                "{\"colorSpace\":\"srgb\",\"components\":[0,0,0],\"alpha\":2}")
                                        + "}")
                        .isValid());
        assertFalse(
                parse(
                                "{\"color\":"
                                        + declaration(
                                                "color",
                                                "{\"colorSpace\":\"srgb\",\"components\":[0,0,0],\"hex\":\"#fff\"}")
                                        + "}")
                        .isValid());
    }

    @Test
    void rejectsMalformedTypeValuesAndUnexpectedCompositeProperties() {
        DesignTokenDocument document =
                parse(
                        "{\"dimension\":"
                                + declaration("dimension", "\"16px\"")
                                + ",\"weight\":"
                                + declaration("fontWeight", "1001")
                                + ",\"bezier\":"
                                + declaration("cubicBezier", "[2,0,1,1]")
                                + ",\"color\":"
                                + declaration(
                                        "color",
                                        "{\"colorSpace\":\"hsl\",\"components\":[360,0,0]}")
                                + ",\"shadow\":"
                                + declaration(
                                        "shadow",
                                        SHADOW.replace(
                                                "\"inset\":true", "\"inset\":true,\"extra\":1"))
                                + ",\"missing\":{\"$value\":1}}");
        assertFalse(document.isValid());
        assertEquals(6, document.getIssues().size());
        assertTrue(document.getTokens().stream().allMatch(t -> t.getResolvedValue() == null));
    }

    @Test
    void inheritsGroupsRootAndDeprecationButReplacesWholeTokens() {
        DesignTokenDocument document =
                parse(
                        "{\"base\":{\"$type\":\"number\",\"$deprecated\":true,\"nested\":{\"keep\":{\"$value\":1},\"replace\":{\"$value\":2,\"$description\":\"old\"}},\"$root\":{\"$value\":3}},\"child\":{\"$extends\":\"{base}\",\"$deprecated\":false,\"nested\":{\"replace\":{\"$value\":4}}},\"alias\":{\"$value\":\"{child.$root}\"}}");
        assertTrue(document.isValid(), document.getIssues().toString());
        assertEquals(1, token(document, "child", "nested", "keep").getResolvedValue().intValue());
        assertEquals(
                4, token(document, "child", "nested", "replace").getResolvedValue().intValue());
        assertNull(token(document, "child", "nested", "replace").getDescription());
        assertFalse(token(document, "child", "nested", "keep").getDeprecated().booleanValue());
        assertEquals("number", token(document, "alias").getType());
        assertEquals(3, token(document, "alias").getResolvedValue().intValue());
    }

    @Test
    void resolvesCurlyCompositeAndEscapedPropertyReferencesWithoutStringInterpolation() {
        DesignTokenDocument document =
                parse(
                        "{\"size/~\":"
                                + declaration("dimension", DIMENSION)
                                + ",\"unit\":{\"$type\":\"dimension\",\"$value\":{\"value\":{\"$ref\":\"#/size~1~0/$value/value\"},\"unit\":\"rem\"}},\"border\":"
                                + declaration(
                                        "border",
                                        "{\"color\":"
                                                + COLOR
                                                + ",\"width\":\"{unit}\",\"style\":\"solid\"}")
                                + ",\"family\":"
                                + declaration("fontFamily", "\"prefix {unit}\"")
                                + "}");
        assertTrue(document.isValid(), document.getIssues().toString());
        assertEquals(
                16,
                token(document, "border")
                        .getResolvedValue()
                        .path("width")
                        .path("value")
                        .intValue());
        assertEquals(
                List.of("size/~"), token(document, "unit").getAliases().getFirst().getTargetPath());
        assertEquals("prefix {unit}", token(document, "family").getResolvedValue().asText());
    }

    @Test
    void supportsNormativeTokenLevelJsonPointerFormsAndNullNeverBecomesValid() {
        DesignTokenDocument document =
                parse(
                        "{\"base\":"
                                + declaration("dimension", DIMENSION)
                                + ",\"alias\":{\"$ref\":\"#/base/$value\"},\"whole\":{\"$ref\":\"#/base\"},\"component\":{\"$ref\":\"#/base/$value/value\",\"$type\":\"number\"},\"g\":{\"$root\":{\"$ref\":\"#/base\"}}}");
        assertTrue(document.isValid(), document.getIssues().toString());
        assertEquals("dimension", token(document, "alias").getType());
        assertEquals(16, token(document, "whole").getResolvedValue().path("value").intValue());
        assertEquals(16, token(document, "component").getResolvedValue().intValue());
        assertEquals(
                "#/base/$value", token(document, "alias").getDeclaredValue().path("$ref").asText());
        assertEquals("dimension", token(document, "g", "$root").getType());
        assertFalse(parse("{\"n\":" + declaration("number", "null") + "}").isValid());
        DesignTokenDocument invalid =
                parse(
                        "{\"border\":"
                                + declaration(
                                        "border",
                                        "{\"color\":\"{absentColor}\",\"width\":\"{absentWidth}\",\"style\":\"solid\"}")
                                + "}");
        assertFalse(invalid.isValid());
        assertEquals(2, token(invalid, "border").getAliases().size());
        assertNull(token(invalid, "border").getResolvedValue());
        assertTrue(
                parse(
                                "{\"version\":{\"$type\":\"number\",\"$value\":1},\"sets\":{},\"modifiers\":{},\"resolutionOrder\":{}}")
                        .isValid());
    }

    @Test
    void reportsAllCycleMembersMissingAndMismatchedAliasesAsNull() {
        DesignTokenDocument document =
                parse(
                        "{\"a\":{\"$type\":\"number\",\"$value\":\"{b}\"},\"b\":{\"$value\":\"{a}\"},\"missing\":{\"$value\":\"{absent}\"},\"bad\":{\"$type\":\"color\",\"$value\":\"{n}\"},\"n\":"
                                + declaration("number", "2")
                                + "}");
        assertFalse(document.isValid());
        for (String name : List.of("a", "b", "missing", "bad"))
            assertNull(token(document, name).getResolvedValue());
        assertEquals("cycle", token(document, "a").getAliases().getFirst().getStatus());
        assertEquals("cycle", token(document, "b").getAliases().getFirst().getStatus());
        assertEquals("missing", token(document, "missing").getAliases().getFirst().getStatus());
        assertTrue(
                document.getIssues().stream()
                        .anyMatch(i -> i.getCode().equals("REFERENCE_TYPE_MISMATCH")));
        assertFalse(parse("{\"a\":{\"$extends\":\"{b}\"},\"b\":{\"$extends\":\"{a}\"}}").isValid());
    }

    private String resolver() {
        return """
            {"version":"2025.10","$defs":{"base":{"n":{"$type":"number","$value":1,"$description":"base"},"alias":{"$value":"{n}"}}},
            "sets":{"base":{"sources":[{"$ref":"#/$defs/base"}]},"override":{"$ref":"#/sets/base","sources":[{"n":{"$type":"number","$value":2}}]}},
            "modifiers":{"density":{"contexts":{"compact":[{"n":{"$type":"number","$value":3}}],"comfortable":[{"n":{"$type":"number","$value":"bad"}}]}}},
            "resolutionOrder":[{"$ref":"#/sets/base"},{"$ref":"#/sets/override"},{"$ref":"#/modifiers/density"}]}
            """;
    }

    @Test
    void resolvesOrderedWholeTokensBeforeAliasesAndDistinguishesAllPermutationsFromSelectedView() {
        DesignTokenDocument all = parse(resolver());
        assertFalse(all.isValid());
        assertEquals(Map.of("density", "compact"), all.getInputs());
        assertNull(all.getModifiers().getFirst().getDefaultContext());
        assertEquals(3, token(all, "alias").getResolvedValue().intValue());
        assertNull(token(all, "n").getDescription());
        DesignTokenDocument compact =
                parser.parse(
                        resolver().getBytes(StandardCharsets.UTF_8), Map.of("density", "compact"));
        assertTrue(compact.isValid(), compact.getIssues().toString());
        assertFalse(parser.parse(resolver().getBytes(StandardCharsets.UTF_8), Map.of()).isValid());
        assertFalse(
                parser.parse(
                                resolver().getBytes(StandardCharsets.UTF_8),
                                Map.of("density", "unknown"))
                        .isValid());
        assertFalse(
                parser.parse(
                                resolver().getBytes(StandardCharsets.UTF_8),
                                Map.of("density", "compact", "extra", "x"))
                        .isValid());
        assertEquals(
                List.of(List.of("n"), List.of("alias")), all.getSets().getFirst().getTokenPaths());
    }

    @Test
    void supportsInlineEntriesAndDeclaredDefaultsAndRejectsExternalOrForbiddenReferences() {
        String inline =
                "{\"version\":\"2025.10\",\"resolutionOrder\":[{\"name\":\"theme\",\"type\":\"modifier\",\"default\":\"day\",\"contexts\":{\"day\":[{\"n\":"
                        + declaration("number", "1")
                        + "}],\"night\":[]}}]}";
        DesignTokenDocument document = parse(inline);
        assertTrue(document.isValid(), document.getIssues().toString());
        assertEquals(Map.of("theme", "day"), document.getInputs());
        assertTrue(parser.parse(inline.getBytes(StandardCharsets.UTF_8), Map.of()).isValid());
        for (String ref :
                List.of(
                        "https://example.invalid/tokens.json",
                        "file:///etc/passwd",
                        "#/resolutionOrder/0",
                        "#/modifiers/theme")) {
            String input =
                    "{\"version\":\"2025.10\",\"sets\":{\"x\":{\"sources\":[{\"$ref\":\""
                            + ref
                            + "\"}]}},\"resolutionOrder\":[{\"$ref\":\"#/sets/x\"}]}";
            assertFalse(parse(input).isValid(), ref);
        }
    }

    @Test
    void strictUtf8JsonDuplicateKeysRootAndBomBoundary() {
        assertTrue(parse("\uFEFF{}").isValid());
        for (String input :
                List.of("{\"a\":1,\"a\":2}", "{} {}", "{\"a\":NaN}", "{\"x\":1,}", "[]", "null")) {
            assertThrows(PrototypeHubException.class, () -> parse(input), input);
        }
        assertEquals(
                "PROTOTYPE_HUB_TOKEN_PACKAGE_INVALID_UTF8",
                assertThrows(
                                PrototypeHubException.class,
                                () -> parser.parse(new byte[] {(byte) 0xc3, 0x28}))
                        .getCode());
        assertThrows(
                PrototypeHubException.class,
                () -> parser.parse(new byte[DesignTokenParser.MAX_BYTES + 1]));
    }

    @Test
    void resourceLimitsFailInsteadOfReturningFalseValidOrTruncatedTokens() {
        assertThrows(
                PrototypeHubException.class,
                () -> parse("{\"g\":".repeat(33) + "{}" + "}".repeat(33)));
        StringBuilder many = new StringBuilder("{");
        for (int i = 0; i < 5001; i++) {
            if (i > 0) many.append(',');
            many.append('"').append(i).append("\":").append(declaration("number", "1"));
        }
        many.append('}');
        assertThrows(PrototypeHubException.class, () -> parse(many.toString()));
        String contexts =
                "{\"version\":\"2025.10\",\"modifiers\":{\"x\":{\"contexts\":%s}},\"resolutionOrder\":[{\"$ref\":\"#/modifiers/x\"}]}";
        StringBuilder choices = new StringBuilder("{");
        for (int i = 0; i < 17; i++) {
            if (i > 0) choices.append(',');
            choices.append('"').append(i).append("\":[]");
        }
        choices.append('}');
        assertThrows(PrototypeHubException.class, () -> parse(contexts.formatted(choices)));
        StringBuilder chain = new StringBuilder("{");
        for (int i = 0; i < 66; i++) {
            if (i > 0) chain.append(',');
            chain.append('"').append(i).append("\":{\"$value\":\"{").append(i + 1).append("}\"}");
        }
        chain.append(",\"66\":").append(declaration("number", "1")).append('}');
        assertThrows(PrototypeHubException.class, () -> parse(chain.toString()));
    }

    @Test
    void boundsNodeTraversalAndSerializedAliasExpansion() {
        String nodes = "{\"$extensions\":{\"org.example\":[" + "0,".repeat(100_000) + "0]}}";
        assertEquals(
                "PROTOTYPE_HUB_TOKEN_PACKAGE_LIMIT_EXCEEDED",
                assertThrows(PrototypeHubException.class, () -> parse(nodes)).getCode());
        StringBuilder input =
                new StringBuilder("{\"base\":")
                        .append(declaration("fontFamily", "\"" + "x".repeat(300_000) + "\""));
        for (int i = 0; i < 15; i++)
            input.append(",\"alias").append(i).append("\":{\"$value\":\"{base}\"}");
        input.append('}');
        assertEquals(
                "PROTOTYPE_HUB_TOKEN_PACKAGE_LIMIT_EXCEEDED",
                assertThrows(PrototypeHubException.class, () -> parse(input.toString())).getCode());
    }

    @Test
    void validatesDormantResolverReferencesAndReferenceOverrideShallowness() {
        String input =
                "{\"version\":\"2025.10\",\"sets\":{\"unused\":{\"sources\":[{\"$ref\":\"https://example.invalid/x\"}]}},\"resolutionOrder\":[]}";
        assertFalse(parse(input).isValid());
        String reference =
                resolver()
                        .replace(
                                "\"sources\":[{\"n\":{\"$type\":\"number\",\"$value\":2}}]",
                                "\"sources\":[]");
        DesignTokenDocument all = parse(reference);
        assertEquals(3, token(all, "alias").getResolvedValue().intValue());
        assertTrue(
                all.getSets().stream()
                        .filter(set -> set.getName().equals("override"))
                        .findFirst()
                        .orElseThrow()
                        .getTokenPaths()
                        .isEmpty());
    }

    @Test
    void capsIssuesAndLeavesZeroTokenDocumentsHonest() {
        StringBuilder invalid = new StringBuilder("{");
        for (int i = 0; i < 140; i++) {
            if (i > 0) invalid.append(',');
            invalid.append('"').append(i).append("\":{\"$type\":\"number\",\"$value\":\"bad\"}");
        }
        invalid.append('}');
        DesignTokenDocument report = parse(invalid.toString());
        assertEquals(128, report.getIssues().size());
        assertTrue(report.isTruncated());
        assertFalse(report.isValid());
        DesignTokenDocument empty = parse("{\"empty\":{}}");
        assertTrue(empty.isValid());
        assertEquals(0, empty.getTokenCount());
        assertEquals(1, empty.getGroupCount());
    }
}
