package com.gaiys.proto.prototypehub.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest;
import com.gaiys.proto.prototypehub.storage.scanner.PrototypePackageQualityScanner;
import com.gaiys.proto.prototypehub.web.response.RevisionQualityResponse.Code;
import com.gaiys.proto.prototypehub.web.response.RevisionQualityResponse.Severity;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrototypePackageQualityScannerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HASH = "a".repeat(64);
    @TempDir Path root;

    @Test
    void fixedManifestScansNestedLiteralReferencesWithoutChangingFiles() throws Exception {
        var source = new LinkedHashMap<String, byte[]>();
        source.put(
                "index.html",
                bytes(
                        """
                <HTML><head><LINK HREF="assets/main.css?x=1&amp;y=2" rel="stylesheet"></head>
                <body><a href="nested/%E4%B8%AD%E6%96%87.html#section">Go</a>
                <img src="assets/a+b.svg"><img src="assets/space%20name.svg">
                <div style="background:url('assets/a+b.svg')"></div>
                <style>.x{background: URL(assets/a+b.svg)}</style></body></HTML>
                """));
        source.put(
                "nested/中文.html",
                bytes("<a href='../index.html'>Home</a><img src='../assets/a+b.svg'>"));
        source.put("assets/main.css", bytes("@import 'nested.css';.x{background:url(a+b.svg)}"));
        source.put(
                "assets/nested.css",
                bytes(
                        "/* url(missing.svg) */ .x{content:'url(fake.svg)';background:URL('space\\20 name.svg')}"));
        source.put("assets/a+b.svg", bytes("<svg/>"));
        source.put("assets/space name.svg", bytes("<svg/>"));
        var revision = revision(source);
        var result = scanner().scan(revision);
        assertTrue(result.complete());
        assertEquals("version", result.versionId());
        assertEquals("revision", result.revisionId());
        assertEquals(HASH, result.manifestSha256());
        assertEquals(6, result.summary().files());
        assertEquals(4, result.summary().scannedDocuments());
        assertEquals(0, result.summary().errors());
        assertEquals(1, result.summary().warnings());
        assertEquals(1, result.summary().uncheckedReferences().fragment());
        assertEquals(
                List.of(Code.chinese_path), result.findings().stream().map(f -> f.code()).toList());
        assertEquals(Severity.warning, result.findings().getFirst().severity());
        assertEquals(result, scanner().scan(revision));
        for (var file : source.entrySet())
            assertArrayEquals(file.getValue(), Files.readAllBytes(site().resolve(file.getKey())));
    }

    @Test
    void distinguishesMissingResourcesAndNavigationAndDeduplicatesRepeatedTargets()
            throws Exception {
        var revision =
                revision(
                        Map.of(
                                "index.html",
                                bytes(
                                        "<img src='missing.png'><img src='missing.png?again'><a href='lost.html'>Lost</a><iframe src='gone.html'></iframe>")));
        var result = scanner().scan(revision);
        assertTrue(result.complete());
        assertEquals(3, result.summary().errors());
        assertEquals(4, result.summary().checkedReferences());
        assertEquals(3, result.summary().findingsTotal());
        assertEquals(
                List.of("lost.html", "gone.html", "missing.png"),
                result.findings().stream().map(f -> f.targetPath()).toList());
        assertEquals(Code.broken_internal_link, result.findings().getFirst().code());
        assertTrue(result.findings().stream().allMatch(f -> f.sourcePath().equals("index.html")));
    }

    @Test
    void missingPhysicalFilesAndEntryArePartialWithoutFilesystemPaths() throws Exception {
        var revision =
                revision(
                        Map.of(
                                "index.html",
                                bytes("<img src='photo.png'>"),
                                "photo.png",
                                bytes("png")));
        Files.delete(site().resolve("photo.png"));
        var result = scanner().scan(revision);
        assertFalse(result.complete());
        assertEquals(2, result.summary().errors());
        assertTrue(result.findings().stream().anyMatch(f -> f.code() == Code.file_missing));
        assertTrue(result.findings().stream().anyMatch(f -> f.code() == Code.missing_resource));
        Files.delete(site().resolve("index.html"));
        var entry = scanner().scan(revision);
        assertFalse(entry.complete());
        assertEquals(1, entry.summary().skippedDocuments());
        assertTrue(entry.findings().stream().anyMatch(f -> f.code() == Code.entry_missing));
        assertFalse(JSON.writeValueAsString(entry).contains(root.toString()));
    }

    @Test
    void absentManifestEntryIsReportedWithoutFallingBackToAnotherFile() throws Exception {
        var revision = revision(Map.of("other.html", bytes("hello")));
        var result = scanner().scan(revision);
        assertFalse(result.complete());
        assertEquals("index.html", result.entryFile());
        assertEquals(Code.entry_missing, result.findings().getFirst().code());
    }

    @Test
    void externalRootFragmentAndSchemesStayUncheckedAndInjectedScriptsAreExcluded()
            throws Exception {
        var revision =
                revision(
                        Map.of(
                                "index.html",
                                bytes(
                                        """
                <script data-prototype-hub-inspector src='/api/prototype-hub/public/inspector.js'></script>
                <script data-protohub-inspector src='/api/protohub/public/inspector.js'></script>
                <a href='https://127.0.0.1:1/no-network'>external</a><img src='//127.0.0.1/no-network'>
                <img src='/server-root.png'><a href='#anchor'>fragment</a>
                <img src='data:image/png;base64,AA'><a href='mailto:test@example.org'>mail</a>
                <a href='javascript:if(true){alert("https://127.0.0.1")}'>script</a>
                <script>fetch('dynamic-missing.json')</script>
                """)));
        var result = scanner().scan(revision);
        assertTrue(result.complete());
        assertTrue(result.findings().isEmpty());
        assertEquals(2, result.summary().uncheckedReferences().external());
        assertEquals(1, result.summary().uncheckedReferences().rootAbsolute());
        assertEquals(1, result.summary().uncheckedReferences().fragment());
        assertEquals(3, result.summary().uncheckedReferences().unsupportedScheme());
        assertEquals(7, result.summary().checkedReferences());
    }

    @Test
    void baseHrefAndSrcsetPreserveDataCommasAndRelativeResolution() throws Exception {
        var revision =
                revision(
                        Map.of(
                                "index.html",
                                        bytes(
                                                "<base href='assets/'><img src='icon.svg'><img srcset='data:image/png;base64,AA 1x, icon.svg 2x, missing.svg 3x'>"),
                                "assets/icon.svg", bytes("svg")));
        var result = scanner().scan(revision);
        assertTrue(result.complete());
        assertEquals(1, result.summary().errors());
        assertEquals("assets/missing.svg", result.findings().getFirst().targetPath());
        assertEquals(1, result.summary().uncheckedReferences().unsupportedScheme());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/host-root/",
                "https://example.invalid/",
                "//example.invalid/",
                "https://prototype-hub.invalid/"
            })
    void nonPackageBaseIsCountedWithoutInventingMissingLocalFiles(String base) throws Exception {
        var revision =
                revision(
                        Map.of(
                                "index.html",
                                bytes("<base href='" + base + "'><img src='icon.svg'>")));
        var result = scanner().scan(revision);
        assertTrue(result.findings().isEmpty());
        assertTrue(result.complete());
        assertEquals(
                1,
                result.summary().uncheckedReferences().rootAbsolute()
                        + result.summary().uncheckedReferences().external());
    }

    @Test
    void htmlAndCssHonorDeclaredCharsetsAndUtf16Bom() throws Exception {
        var latin = Charset.forName("windows-1252");
        var revision =
                revision(
                        Map.of(
                                "index.html",
                                        "<meta charset='windows-1252'><link href='café.css'>"
                                                .getBytes(latin),
                                "café.css",
                                        "@charset \"windows-1252\"; .x{background:url(café.svg)}"
                                                .getBytes(latin),
                                "utf16.css",
                                        "\ufeff.x{background:url(café.svg)}"
                                                .getBytes(StandardCharsets.UTF_16LE),
                                "café.svg", bytes("svg")));
        var result = scanner().scan(revision);
        assertTrue(result.complete());
        assertEquals(3, result.summary().scannedDocuments());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void malformedTextAndPerDocumentBudgetAreExplicitlyPartial() throws Exception {
        var revision =
                revision(
                        Map.of(
                                "index.html", bytes("<link href='bad.css'><link href='huge.css'>"),
                                "bad.css", new byte[] {(byte) 0xff},
                                "huge.css",
                                        new byte
                                                [PrototypePackageQualityScanner.LIMITS
                                                                .maxTextBytes()
                                                        + 1]));
        var result = scanner().scan(revision);
        assertFalse(result.complete());
        assertEquals(2, result.summary().skippedDocuments());
        assertEquals(1, result.summary().scannedDocuments());
        assertEquals(
                List.of(Code.text_unreadable, Code.document_limit),
                result.findings().stream().map(f -> f.code()).toList());
    }

    @Test
    void cumulativeTextBudgetSkipsRemainingDocumentsAndCountsActualBytes() throws Exception {
        var files = new LinkedHashMap<String, byte[]>();
        files.put("index.html", bytes("<p>index</p>"));
        for (int i = 0; i < 17; i++)
            files.put(
                    "part-%02d.css".formatted(i),
                    new byte[PrototypePackageQualityScanner.LIMITS.maxTextBytes()]);
        var result = scanner().scan(revision(files));
        assertFalse(result.complete());
        assertEquals(2, result.summary().skippedDocuments());
        assertTrue(result.summary().scannedBytes() <= result.limits().maxTotalTextBytes());
        assertTrue(result.findings().stream().allMatch(f -> f.code() == Code.document_limit));
    }

    @Test
    void referenceBudgetIsBoundedAndMarksRemainingDocumentsAsSkipped() throws Exception {
        String html = "<img src='x'>".repeat(100_001);
        var result =
                scanner()
                        .scan(
                                revision(
                                        Map.of(
                                                "index.html",
                                                bytes(html),
                                                "later.css",
                                                bytes("a{background:url(missing.png)}"))));
        assertFalse(result.complete());
        assertEquals(100_000, result.summary().checkedReferences());
        assertEquals(2, result.summary().skippedDocuments());
        assertTrue(result.findings().stream().anyMatch(f -> f.code() == Code.reference_limit));
        assertEquals(1, result.summary().errors());
    }

    @Test
    void retainedFindingBudgetDoesNotLoseTotalCountsOrClaimComplete() throws Exception {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 1015; i++) html.append("<img src='missing-").append(i).append(".png'>");
        var result = scanner().scan(revision(Map.of("index.html", bytes(html.toString()))));
        assertFalse(result.complete());
        assertEquals(1015, result.summary().errors());
        assertEquals(1015, result.summary().findingsTotal());
        assertEquals(15, result.summary().omittedFindings());
        assertEquals(1000, result.findings().size());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../outside.png",
                "%2e%2e/outside.png",
                "%00.png",
                "%ff.png",
                "a\\b.png",
                "%GG",
                "file:///etc/passwd"
            })
    void unsafeOrUnsupportedReferencesNeverReadOutsideThePackage(String reference)
            throws Exception {
        Files.writeString(root.resolve("outside.png"), "not visible");
        var revision = revision(Map.of("index.html", bytes("<img src='" + reference + "'>")));
        var result = scanner().scan(revision);
        if (reference.startsWith("file:"))
            assertEquals(1, result.summary().uncheckedReferences().unsupportedScheme());
        else assertEquals(Code.invalid_reference, result.findings().getFirst().code());
        assertFalse(JSON.writeValueAsString(result).contains("not visible"));
    }

    @Test
    void overlongReferenceIsBoundedAndInvalidBaseDoesNotSilentlyPass() throws Exception {
        var result =
                scanner()
                        .scan(
                                revision(
                                        Map.of(
                                                "index.html",
                                                bytes(
                                                        "<base href='%XX'><img src='"
                                                                + "a".repeat(1500)
                                                                + "'>"))));
        assertEquals(1, result.summary().errors());
        assertTrue(result.findings().getFirst().reference().length() <= 1024);
        assertEquals(Code.invalid_reference, result.findings().getFirst().code());
    }

    @Test
    void symbolicLinksAreRejectedBeforeReadingTheirTargets() throws Exception {
        var revision = revision(Map.of("index.html", bytes("hello")));
        Path outside = Files.writeString(root.resolve("outside.html"), "private contents");
        Files.delete(site().resolve("index.html"));
        Files.createSymbolicLink(site().resolve("index.html"), outside);
        assertThrows(PrototypeHubException.class, () -> scanner().scan(revision));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "{}",
                "[]",
                "{\"formatVersion\":5,\"entryFile\":\"index.html\",\"files\":[],\"pages\":[]}",
                "{\"formatVersion\":1,\"entryFile\":\"../private.html\",\"files\":[],\"pages\":[]}"
            })
    void malformedOrUnsafeManifestsReturnTheExistingControlledError(String json) throws Exception {
        var revision = revision(Map.of("index.html", bytes("hello")));
        revision.setManifestJson(json);
        var error = assertThrows(PrototypeHubException.class, () -> scanner().scan(revision));
        assertEquals("PROTOTYPE_HUB_MANIFEST_INVALID", error.getCode());
    }

    @Test
    void manifestValidationRejectsDuplicateInvalidOrOverBudgetFiles() throws Exception {
        var revision = revision(Map.of("index.html", bytes("hello")));
        var cases =
                List.of(
                        List.of(file("index.html", 1, HASH), file("index.html", 1, HASH)),
                        List.of(file("../outside.html", 1, HASH)),
                        List.of(file("index.html", -1, HASH)),
                        List.of(file("index.html", 1, "bad")),
                        List.of(file("index.html", Long.MAX_VALUE, HASH)));
        for (var files : cases) {
            revision.setManifestJson(
                    JSON.writeValueAsString(
                            new PrototypeManifest(1, "index.html", files, List.of())));
            var error = assertThrows(PrototypeHubException.class, () -> scanner().scan(revision));
            assertEquals("PROTOTYPE_HUB_MANIFEST_INVALID", error.getCode());
        }
    }

    private PrototypePackageQualityScanner scanner() {
        var properties = new PrototypeHubStorageProperties();
        properties.setRoot(root);
        return new PrototypePackageQualityScanner(properties);
    }

    private PrototypeRevisionEntity revision(Map<String, byte[]> files) throws Exception {
        Files.createDirectories(site());
        List<PrototypeManifest.FileEntry> entries = new ArrayList<>();
        for (var file : files.entrySet()) {
            Path target = site().resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
            entries.add(file(file.getKey(), file.getValue().length, HASH));
        }
        var revision = new PrototypeRevisionEntity();
        revision.setId("revision");
        revision.setVersionId("version");
        revision.setProjectId("project");
        revision.setStoragePath("projects/project/versions/revision/site");
        revision.setManifestSha256(HASH);
        revision.setManifestJson(
                JSON.writeValueAsString(
                        new PrototypeManifest(1, "index.html", entries, List.of())));
        return revision;
    }

    private static PrototypeManifest.FileEntry file(String path, long size, String hash) {
        return new PrototypeManifest.FileEntry(path, size, hash, "application/octet-stream");
    }

    private Path site() {
        return root.resolve("projects/project/versions/revision/site");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
