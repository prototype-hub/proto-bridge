package com.gaiys.proto.prototypehub.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.copy.PrototypeCopyExporter;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse.Entry;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse.Occurrence;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse.Report;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PrototypeCopyExporterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PrototypeCopyExporter exporter = new PrototypeCopyExporter(JSON);

    @Test
    void csvIsBomPrefixedRfc4180WithCompleteFixedIdentityAndOccurrences() throws Exception {
        var occurrences = List.of(new Occurrence(1, "DOCUMENT_TITLE"), new Occurrence(3, "TEXT"));
        var source =
                new Entry(
                        "static-id",
                        "STATIC",
                        "原文,\"quoted\"\r\nnext line",
                        "术语",
                        "备注\n第二行",
                        "https://example.test/work?x=1&y=2",
                        2,
                        occurrences,
                        4,
                        10,
                        20);
        var manual = entry("manual-id", "人工动态文案", "", "");
        var report = report("READY", List.of(source, manual));
        byte[] bytes = exporter.bytes(report, "csv");
        assertArrayEquals(
                new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf},
                java.util.Arrays.copyOf(bytes, 3));
        String raw = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(raw.contains("\"原文,\"\"quoted\"\"\r\nnext line\""));
        var rows = csv(bytes);
        assertEquals(
                List.of(
                        "recordType",
                        "projectId",
                        "versionId",
                        "revisionId",
                        "pageId",
                        "snapshotId",
                        "inventoryVersion",
                        "pagePath",
                        "manifestSha256",
                        "fileSha256",
                        "extractorVersion",
                        "extractionState",
                        "limitReason",
                        "staticComplete",
                        "dynamicTextGap",
                        "id",
                        "kind",
                        "text",
                        "businessTerm",
                        "note",
                        "requirementUrl",
                        "occurrenceCount",
                        "occurrences",
                        "configVersion",
                        "createdAt",
                        "updatedAt"),
                rows.getFirst());
        assertEquals(
                4, rows.size(), "One context row and every STATIC/MANUAL entry must be exported");
        var context =
                List.of(
                        "project",
                        "logical-version",
                        "fixed-old-revision",
                        "fixed-page",
                        "snapshot",
                        "7",
                        "nested/中文.html",
                        "a".repeat(64),
                        "b".repeat(64),
                        "1",
                        "READY",
                        "",
                        "true",
                        "true");
        assertEquals("SNAPSHOT", rows.get(1).getFirst());
        assertTrue(rows.get(1).subList(15, 26).stream().allMatch(String::isEmpty));
        for (var row : rows.subList(1, rows.size())) {
            assertEquals(26, row.size());
            assertEquals(context, row.subList(1, 15));
        }
        assertEquals("ENTRY", rows.get(2).getFirst());
        assertEquals("static-id", rows.get(2).get(15));
        assertEquals("STATIC", rows.get(2).get(16));
        assertEquals(source.text(), rows.get(2).get(17));
        assertEquals(source.businessTerm(), rows.get(2).get(18));
        assertEquals(source.note(), rows.get(2).get(19));
        assertEquals(source.requirementUrl(), rows.get(2).get(20));
        assertEquals("2", rows.get(2).get(21));
        assertEquals(JSON.valueToTree(occurrences), JSON.readTree(rows.get(2).get(22)));
        assertEquals(List.of("4", "10", "20"), rows.get(2).subList(23, 26));
        assertEquals("manual-id", rows.get(3).get(15));
        assertEquals("MANUAL", rows.get(3).get(16));
        assertEquals("人工动态文案", rows.get(3).get(17));
        assertEquals("0", rows.get(3).get(21));
        assertEquals("[]", rows.get(3).get(22));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY", "LIMIT_EXCEEDED"})
    void emptyInventoryStillCarriesFullContextAndNullableMetadata(String state) throws Exception {
        var report = report(state, List.of());
        var rows = csv(exporter.bytes(report, "csv"));
        assertEquals(2, rows.size());
        assertEquals("SNAPSHOT", rows.get(1).getFirst());
        assertEquals(state, rows.get(1).get(11));
        assertEquals("LIMIT_EXCEEDED".equals(state) ? "SOURCE_BYTES" : "", rows.get(1).get(12));
        assertEquals("LIMIT_EXCEEDED".equals(state) ? "false" : "true", rows.get(1).get(13));
        assertEquals("true", rows.get(1).get(14));
        assertTrue(rows.get(1).subList(15, 26).stream().allMatch(String::isEmpty));
        var json = JSON.readTree(exporter.bytes(report, "json"));
        assertEquals(1, json.path("formatVersion").asInt());
        assertEquals(
                report.snapshot(),
                JSON.treeToValue(json.get("snapshot"), CopyInventoryResponse.class));
        assertTrue(json.get("entries").isEmpty());
        if ("EMPTY".equals(state)) assertTrue(json.path("snapshot").get("limitReason").isNull());
    }

    @ParameterizedTest
    @MethodSource("formulaCases")
    void protectsFormulaPronePlainTextCellsWithoutChangingTheirOriginalContent(
            String raw, String expected) throws Exception {
        var report = report("READY", List.of(entry("manual", raw, raw, raw)));
        var row = csv(exporter.bytes(report, "csv")).get(2);
        assertEquals(expected, row.get(17));
        assertEquals(expected, row.get(18));
        assertEquals(expected, row.get(19));
        assertEquals(
                raw,
                JSON.readValue(exporter.bytes(report, "json"), Report.class)
                        .entries()
                        .getFirst()
                        .text());
    }

    static Stream<Arguments> formulaCases() {
        return Stream.of(
                Arguments.of("=SUM(1,2)", "'=SUM(1,2)"),
                Arguments.of("+1", "'+1"),
                Arguments.of("-10", "'-10"),
                Arguments.of("@command", "'@command"),
                Arguments.of(" \t=SUM(1,2)", "' \t=SUM(1,2)"),
                Arguments.of("\u2003\u00a0+1", "'\u2003\u00a0+1"),
                Arguments.of("\u0001@command", "'\u0001@command"),
                Arguments.of("\ufeff=1", "'\ufeff=1"),
                Arguments.of("\tordinary", "'\tordinary"),
                Arguments.of("\rordinary", "'\rordinary"),
                Arguments.of("\nordinary", "'\nordinary"),
                Arguments.of("ordinary=1", "ordinary=1"),
                Arguments.of(" 中文 + 标点,\"quoted\"", " 中文 + 标点,\"quoted\""),
                Arguments.of("'=SUM(1,2)", "'=SUM(1,2)"),
                Arguments.of("", ""));
    }

    @Test
    void jsonRoundTripsEveryStoredStringAndFullReportWithoutCsvEscapesOrBom() throws Exception {
        var stored = " \t=SUM(1,2)\r\n中文 \"quote\" \\ path 😀 e\u0301\u00a0";
        var report =
                report(
                        "READY",
                        List.of(
                                entry("manual", stored, "'原文", stored),
                                new Entry(
                                        "static",
                                        "STATIC",
                                        "<script>plain text</script>",
                                        "",
                                        "",
                                        "",
                                        1,
                                        List.of(new Occurrence(42, "ALT")),
                                        0,
                                        1,
                                        2)));
        byte[] bytes = exporter.bytes(report, "json");
        assertEquals('{', bytes[0]);
        assertEquals(report, JSON.readValue(bytes, Report.class));
        assertEquals(stored, JSON.readTree(bytes).path("entries").get(0).path("text").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "json"})
    void byteBudgetRejectsMultibyteOutputWithDirectBusiness422BeforeReturningAnyBytes(
            String format) {
        String chunk = "界".repeat(1024 * 1024);
        var entries = Collections.nCopies(22, entry("manual", chunk, "", ""));
        assertTrue((long) chunk.length() * entries.size() < PrototypeCopyExporter.MAX_BYTES);
        assertTrue(
                (long) chunk.getBytes(StandardCharsets.UTF_8).length * entries.size()
                        > 64L * 1024 * 1024);
        var error =
                assertThrows(
                        PrototypeHubException.class,
                        () -> exporter.bytes(report("READY", entries), format));
        assertEquals(422, error.getStatus());
        assertEquals("PROTOTYPE_HUB_COPY_INVENTORY_EXPORT_LIMIT_EXCEEDED", error.getCode());
    }

    @Test
    void rejectsUnknownFormatWithExplicit400() {
        var error =
                assertThrows(
                        PrototypeHubException.class,
                        () -> exporter.bytes(report("EMPTY", List.of()), "xlsx"));
        assertEquals(400, error.getStatus());
        assertEquals("PROTOTYPE_HUB_COPY_INVENTORY_FORMAT_INVALID", error.getCode());
    }

    private static Entry entry(String id, String text, String term, String note) {
        return new Entry(id, "MANUAL", text, term, note, "", 0, List.of(), 0, 10, 20);
    }

    private static Report report(String state, List<Entry> entries) {
        var snapshot =
                new CopyInventoryResponse(
                        "project",
                        "logical-version",
                        "fixed-old-revision",
                        "fixed-page",
                        "nested/中文.html",
                        "a".repeat(64),
                        "b".repeat(64),
                        "snapshot",
                        1,
                        state,
                        "LIMIT_EXCEEDED".equals(state) ? "SOURCE_BYTES" : null,
                        !"LIMIT_EXCEEDED".equals(state),
                        true,
                        (int)
                                entries.stream()
                                        .filter(entry -> "STATIC".equals(entry.kind()))
                                        .count(),
                        (int)
                                entries.stream()
                                        .filter(entry -> "MANUAL".equals(entry.kind()))
                                        .count(),
                        7L,
                        123L);
        return new Report(1, snapshot, entries);
    }

    /** Parse the required all-quoted RFC4180 subset independently of exporter internals. */
    private static List<List<String>> csv(byte[] bytes) {
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\ufeff"));
        var rows = new ArrayList<List<String>>();
        int offset = 1;
        while (offset < csv.length()) {
            var row = new ArrayList<String>();
            while (true) {
                assertEquals('"', csv.charAt(offset++), "Every CSV cell must be quoted");
                var value = new StringBuilder();
                boolean closed = false;
                while (offset < csv.length()) {
                    char code = csv.charAt(offset++);
                    if (code != '"') value.append(code);
                    else if (offset < csv.length() && csv.charAt(offset) == '"') {
                        value.append('"');
                        offset++;
                    } else {
                        closed = true;
                        break;
                    }
                }
                assertTrue(closed, "CSV cell must close its quote");
                row.add(value.toString());
                assertTrue(offset < csv.length(), "Every record must end in CRLF");
                if (csv.charAt(offset) == ',') {
                    offset++;
                    continue;
                }
                assertTrue(csv.startsWith("\r\n", offset), "Record separators must be CRLF");
                offset += 2;
                break;
            }
            rows.add(List.copyOf(row));
        }
        return List.copyOf(rows);
    }
}
