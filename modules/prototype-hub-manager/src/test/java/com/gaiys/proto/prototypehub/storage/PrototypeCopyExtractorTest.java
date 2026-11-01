package com.gaiys.proto.prototypehub.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.copy.PrototypeCopyExtractor;
import com.gaiys.proto.prototypehub.storage.copy.PrototypeCopyExtractor.Extracted;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest.FileEntry;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse.Occurrence;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrototypeCopyExtractorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test
    void honorsUtf8FallbackMetaCharsetAndBothUtf16BomOrders() throws Exception {
        assertText("中文 café", bytes("<p>中文 café</p>"));
        assertText(
                "café £",
                "<meta charset='windows-1252'><p>café £</p>"
                        .getBytes(Charset.forName("windows-1252")));
        assertText(
                "中文 café",
                "\ufeff<meta charset='windows-1252'><p>中文 café</p>"
                        .getBytes(StandardCharsets.UTF_16LE));
        assertText("中文 café", "\ufeff<p>中文 café</p>".getBytes(StandardCharsets.UTF_16BE));
        assertText("中文 café", "\ufeff<p>中文 café</p>".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void deduplicatesNormalizedLiteralsAndKeepsEveryOrderedSourceOccurrence() throws Exception {
        var result =
                PrototypeCopyExtractor.literals(
                        bytes(
                                """
<head><title>共享&nbsp; 文案</title></head><body>
<p> 共享　\t文案 </p>
<img src='https://127.0.0.1:1/never-fetch' alt='共享 文案' title='提示&amp;说明'>
<input placeholder='共享 文案' aria-label='共享 文案' value='general-secret'>
<input type='SuBmIt' value='共享 文案'>
<div hidden>source-hidden literal</div>
<div aria-hidden='true'>aria-hidden literal</div>
<span style='display:none'>css-hidden literal</span>
<div>A <b>B</b> C</div><p>&lt;script&gt;literal&lt;/script&gt;</p>
<input type='hidden' value='hidden-secret'><input type='password' value='password-secret'>
<script title='excluded-script'>document.body.innerHTML='executed-secret'</script>
<style title='excluded-style'>.x{content:'style-secret'}</style>
<template title='excluded-template'><p>template-secret</p></template>
<noscript title='excluded-noscript'>noscript-secret</noscript>
<textarea title='excluded-textarea'>textarea-secret</textarea>
</body>
"""));
        assertEquals("READY", result.state());
        assertNull(result.limitReason());
        assertEquals(
                List.of(
                        "共享 文案",
                        "提示&说明",
                        "source-hidden literal",
                        "aria-hidden literal",
                        "css-hidden literal",
                        "A",
                        "B",
                        "C",
                        "<script>literal</script>"),
                result.literals().stream().map(literal -> literal.text()).toList());
        assertEquals(
                List.of(
                        new Occurrence(1, "DOCUMENT_TITLE"), new Occurrence(2, "TEXT"),
                        new Occurrence(3, "ALT"), new Occurrence(5, "PLACEHOLDER"),
                        new Occurrence(6, "ARIA_LABEL"), new Occurrence(7, "BUTTON_VALUE")),
                result.literals().getFirst().occurrences());
        assertEquals(List.of(new Occurrence(4, "TITLE")), result.literals().get(1).occurrences());
        assertEquals(
                IntStream.rangeClosed(1, 14).boxed().toList(),
                result.literals().stream()
                        .flatMap(literal -> literal.occurrences().stream())
                        .map(Occurrence::ordinal)
                        .sorted()
                        .toList());
    }

    @Test
    void collectsOnlyAllowlistedButtonValuesAndDoesNotMergeCaseOrNestedTextNodes()
            throws Exception {
        var result =
                PrototypeCopyExtractor.literals(
                        bytes(
                                """
<input type='button' value='Button'><input type='submit' value='Submit'>
<input type='reset' value='Reset'><input type='text' value='text-secret'>
<button value='button-attribute-secret'>Native button</button>
<p>Case</p><p>case</p><p>outer <b>inner</b> tail</p>
"""));
        assertEquals(
                List.of(
                        "Button",
                        "Submit",
                        "Reset",
                        "Native button",
                        "Case",
                        "case",
                        "outer",
                        "inner",
                        "tail"),
                result.literals().stream().map(literal -> literal.text()).toList());
        assertTrue(
                result.literals().subList(0, 3).stream()
                        .allMatch(
                                literal ->
                                        literal.occurrences()
                                                .getFirst()
                                                .sourceKind()
                                                .equals("BUTTON_VALUE")));
    }

    @Test
    void emptyAndExcludedOnlyDocumentsAreSuccessfulEmptyExtractions() throws Exception {
        for (String html :
                List.of(
                        "",
                        "  \n\t ",
                        "<script>secret</script><style>secret</style><template>secret</template>")) {
            var result = PrototypeCopyExtractor.literals(bytes(html));
            assertEquals("EMPTY", result.state());
            assertNull(result.limitReason());
            assertTrue(result.literals().isEmpty());
        }
    }

    @Test
    void sourceByteBudgetAcceptsExactBoundaryAndNeverReturnsPartialEntries() throws Exception {
        byte[] boundary = new byte[PrototypeCopyExtractor.SOURCE_BYTES];
        Arrays.fill(boundary, (byte) ' ');
        byte[] html = bytes("<p>edge</p>");
        System.arraycopy(html, 0, boundary, 0, html.length);
        assertText("edge", boundary);
        assertLimited(
                "SOURCE_BYTES",
                PrototypeCopyExtractor.literals(Arrays.copyOf(boundary, boundary.length + 1)));
    }

    @Test
    void uniqueEntryBudgetIsAllOrNothingAndDuplicatesDoNotConsumeUniqueSlots() throws Exception {
        String html =
                IntStream.range(0, PrototypeCopyExtractor.STATIC_ENTRIES)
                        .mapToObj(index -> "<p>word-" + index + "</p>")
                        .collect(java.util.stream.Collectors.joining());
        var boundary = PrototypeCopyExtractor.literals(bytes(html + "<p>word-0</p>"));
        assertEquals("READY", boundary.state());
        assertEquals(2000, boundary.literals().size());
        assertEquals(2, boundary.literals().getFirst().occurrences().size());
        assertLimited(
                "STATIC_ENTRIES", PrototypeCopyExtractor.literals(bytes(html + "<p>new-word</p>")));
    }

    @Test
    void occurrenceBudgetRetainsAllPositionsAtBoundaryAndNoPartialRowsAboveIt() throws Exception {
        String html = "<b>same</b>".repeat(PrototypeCopyExtractor.STATIC_OCCURRENCES);
        var boundary = PrototypeCopyExtractor.literals(bytes(html));
        assertEquals("READY", boundary.state());
        assertEquals(1, boundary.literals().size());
        assertEquals(
                IntStream.rangeClosed(1, 10000).boxed().toList(),
                boundary.literals().getFirst().occurrences().stream()
                        .map(Occurrence::ordinal)
                        .toList());
        assertLimited(
                "STATIC_OCCURRENCES", PrototypeCopyExtractor.literals(bytes(html + "<b>same</b>")));
    }

    @Test
    void fixedNestedPageExtractionUsesManifestIdentityAndLeavesAllSourceFilesUnchanged()
            throws Exception {
        byte[] html = bytes("<p>fixed old revision</p><iframe src='frame.html'></iframe>");
        var revision = revision("nested/page.html", html);
        byte[] frame = bytes("<p>frame must not be included</p>");
        Files.write(site().resolve("nested/frame.html"), frame);
        var source = extractor().source(revision, pageId("nested/page.html"));
        assertEquals("project", source.projectId());
        assertEquals("version", source.versionId());
        assertEquals("fixed-revision", source.revisionId());
        assertEquals("nested/page.html", source.pagePath());
        assertEquals(revision.getManifestSha256(), source.manifestSha256());
        assertEquals(hash(html), source.file().sha256());
        var first = extractor().extract(source);
        assertEquals(
                List.of("fixed old revision"),
                first.literals().stream().map(literal -> literal.text()).toList());
        assertEquals(first, extractor().extract(source));
        assertArrayEquals(html, Files.readAllBytes(site().resolve("nested/page.html")));
        assertArrayEquals(frame, Files.readAllBytes(site().resolve("nested/frame.html")));
    }

    @Test
    void rejectsMissingOrAmbiguousPageAndIgnoresFolderIdentity() throws Exception {
        var revision = revision("index.html", bytes("<p>fixed</p>"));
        var file = file("index.html", bytes("<p>fixed</p>"));
        var page = PrototypePageInfo.page("page", "index.html");
        assertCode("PAGE_INVALID", () -> extractor().source(revision, "missing-page"));
        manifest(
                revision,
                List.of(file),
                List.of(PrototypePageInfo.folder("nested", List.of(page)), page));
        assertCode("PAGE_INVALID", () -> extractor().source(revision, page.pageId()));
        var folder = new PrototypePageInfo("folder", page.pageId(), null, true, List.of(page));
        manifest(revision, List.of(file), List.of(folder));
        assertEquals("index.html", extractor().source(revision, page.pageId()).pagePath());
        manifest(
                revision,
                List.of(file),
                List.of(new PrototypePageInfo("folder", page.pageId(), null, true, List.of())));
        assertCode("PAGE_INVALID", () -> extractor().source(revision, page.pageId()));
    }

    @Test
    void verifiesManifestDigestAndRequiresExactlyOneEligibleFile() throws Exception {
        byte[] html = bytes("<p>fixed</p>");
        var revision = revision("index.html", html);
        var page = PrototypePageInfo.page("page", "index.html");
        revision.setManifestSha256("0".repeat(64));
        assertCode("MANIFEST_INVALID", () -> extractor().source(revision, page.pageId()));
        for (var files :
                List.of(
                        List.<FileEntry>of(),
                        List.of(file("index.html", html), file("index.html", html)),
                        List.of(new FileEntry("index.html", -1, hash(html), "text/html")),
                        List.of(new FileEntry("index.html", html.length, "invalid", "text/html")),
                        List.of(
                                new FileEntry(
                                        "index.html",
                                        html.length,
                                        hash(html),
                                        "application/javascript")),
                        List.of(
                                new FileEntry(
                                        "index.html",
                                        html.length,
                                        hash(html),
                                        "text/html-not-real")))) {
            manifest(revision, files, List.of(page));
            assertCode("MANIFEST_INVALID", () -> extractor().source(revision, page.pageId()));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../outside.html",
                "/outside.html",
                "./index.html",
                "a/../index.html",
                "a//index.html",
                "a\\b.html",
                "C:/index.html"
            })
    void rejectsNoncanonicalManifestPathsBeforeAnyFileRead(String path) throws Exception {
        var revision = revision("index.html", bytes("<p>fixed</p>"));
        manifest(
                revision,
                List.of(file(path, bytes("outside"))),
                List.of(new PrototypePageInfo("page", "selected", path, false, List.of())));
        assertCode("MANIFEST_INVALID", () -> extractor().source(revision, "selected"));
    }

    @Test
    void rejectsMissingDifferentSizeAndSameSizeTamperedSourceInsteadOfReturningEmpty()
            throws Exception {
        byte[] html = bytes("<p>before</p>");
        var revision = revision("index.html", html);
        var source = extractor().source(revision, pageId("index.html"));
        Path target = site().resolve("index.html");
        Files.write(target, bytes("<p>tamper</p>"));
        assertCode("SOURCE_CHANGED", () -> extractor().extract(source));
        Files.write(target, bytes("<p>longer than before</p>"));
        assertCode("SOURCE_CHANGED", () -> extractor().extract(source));
        Files.delete(target);
        assertCode("SOURCE_CHANGED", () -> extractor().extract(source));
    }

    @Test
    void oversizedFixedSourceProducesLimitOutcomeWithoutChangingTheSource() throws Exception {
        byte[] html = new byte[PrototypeCopyExtractor.SOURCE_BYTES + 1];
        Arrays.fill(html, (byte) 'x');
        var revision = revision("index.html", html);
        var source = extractor().source(revision, pageId("index.html"));
        assertLimited("SOURCE_BYTES", extractor().extract(source));
        assertArrayEquals(html, Files.readAllBytes(site().resolve("index.html")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsSymlinkFileAndSymlinkAncestorBeforeReadingTheirTargets(boolean ancestor)
            throws Exception {
        byte[] html = bytes("<p>source</p>");
        var revision = revision("nested/index.html", html);
        var source = extractor().source(revision, pageId("nested/index.html"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        byte[] secret = bytes("<p>secret</p>");
        Path externalFile = Files.write(outside.resolve("index.html"), secret);
        Path target = site().resolve("nested/index.html");
        Files.delete(target);
        if (ancestor) {
            Files.delete(target.getParent());
            Files.createSymbolicLink(target.getParent(), outside);
        } else {
            Files.createSymbolicLink(target, externalFile);
        }
        var error = assertThrows(PrototypeHubException.class, () -> extractor().extract(source));
        assertEquals("PROTOTYPE_HUB_STORAGE_PATH_INVALID", error.getCode());
        assertArrayEquals(secret, Files.readAllBytes(externalFile));
    }

    private PrototypeCopyExtractor extractor() {
        var properties = new PrototypeHubStorageProperties();
        properties.setRoot(root);
        return new PrototypeCopyExtractor(properties);
    }

    private PrototypeRevisionEntity revision(String path, byte[] content) throws Exception {
        Path target = site().resolve(path);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        var revision = new PrototypeRevisionEntity();
        revision.setProjectId("project");
        revision.setVersionId("version");
        revision.setId("fixed-revision");
        revision.setStoragePath("projects/project/versions/fixed-storage/site");
        manifest(
                revision,
                List.of(file(path, content)),
                List.of(PrototypePageInfo.page("page", path)));
        return revision;
    }

    private static void manifest(
            PrototypeRevisionEntity revision, List<FileEntry> files, List<PrototypePageInfo> pages)
            throws Exception {
        String json = JSON.writeValueAsString(new PrototypeManifest(1, "index.html", files, pages));
        revision.setManifestJson(json);
        revision.setManifestSha256(hash(bytes(json)));
    }

    private static FileEntry file(String path, byte[] content) throws Exception {
        return new FileEntry(path, content.length, hash(content), "text/html");
    }

    private Path site() {
        return root.resolve("projects/project/versions/fixed-storage/site");
    }

    private static String pageId(String path) {
        return PrototypePageInfo.page("page", path).pageId();
    }

    private static void assertText(String expected, byte[] bytes) throws Exception {
        var result = PrototypeCopyExtractor.literals(bytes);
        assertEquals("READY", result.state());
        assertEquals(
                List.of(expected),
                result.literals().stream().map(literal -> literal.text()).toList());
    }

    private static void assertLimited(String reason, Extracted result) {
        assertEquals("LIMIT_EXCEEDED", result.state());
        assertEquals(reason, result.limitReason());
        assertTrue(
                result.literals().isEmpty(), "A limit must not expose a partial static inventory");
    }

    private static void assertCode(
            String suffix, org.junit.jupiter.api.function.Executable operation) {
        var error = assertThrows(PrototypeHubException.class, operation);
        assertEquals("PROTOTYPE_HUB_COPY_INVENTORY_" + suffix, error.getCode());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
