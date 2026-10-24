package com.gaiys.proto.prototypehub.service.comparison;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.RevisionComparisonRequest;
import com.gaiys.proto.prototypehub.web.response.RevisionComparisonResponse.Counts;
import com.gaiys.proto.prototypehub.web.response.RevisionComparisonResponse.Status;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrototypeRevisionComparisonTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HASH = "a".repeat(64);
    private static final String CHANGED = "b".repeat(64);

    @Test
    void classifiesFilesAndNestedPagesByExactPathAndFirstAliasWithOriginalIds() throws Exception {
        var left =
                revision(
                        "left",
                        List.of(
                                file("root.html"),
                                file("child.html"),
                                file("removed.html"),
                                file("same.html"),
                                file("name.html"),
                                file("main.js"),
                                file("Case.css")),
                        List.of(
                                PrototypePageInfo.folder(
                                        "目录",
                                        List.of(
                                                PrototypePageInfo.page(
                                                        "Root",
                                                        "root.html",
                                                        List.of(
                                                                PrototypePageInfo.page(
                                                                        "Child", "child.html"))),
                                                PrototypePageInfo.page(
                                                        "Ignored alias", "root.html"))),
                                PrototypePageInfo.page("Removed", "removed.html"),
                                PrototypePageInfo.page("Same", "same.html"),
                                PrototypePageInfo.page("Old name", "name.html")));
        var right =
                revision(
                        "right",
                        List.of(
                                new PrototypeManifest.FileEntry(
                                        "root.html", 1, CHANGED, "text/html"),
                                new PrototypeManifest.FileEntry("child.html", 2, HASH, "text/html"),
                                file("added.html"),
                                file("same.html"),
                                file("name.html"),
                                file("main.js"),
                                file("case.css")),
                        List.of(
                                PrototypePageInfo.page(
                                        "Root",
                                        "root.html",
                                        List.of(PrototypePageInfo.page("Child", "child.html"))),
                                PrototypePageInfo.page("Added", "added.html"),
                                PrototypePageInfo.page("Same", "same.html"),
                                PrototypePageInfo.page("New name", "name.html")));
        var result = PrototypeRevisionComparison.compare(left, right, input());
        assertEquals(new Counts(1, 1, 3, 1), result.summary().pages());
        assertEquals(new Counts(2, 2, 2, 3), result.summary().files());
        assertEquals(5, result.pages().getTotal());
        assertEquals(6, result.files().getTotal());
        var root =
                result.pages().getData().stream()
                        .filter(p -> p.path().equals("root.html"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("Root", root.left().name());
        assertEquals(PrototypePageInfo.page("", "root.html").pageId(), root.left().pageId());
        assertEquals(Status.changed, root.status());
        var added = result.pages().getData().getFirst();
        assertEquals("added.html", added.path());
        assertEquals(Status.added, added.status());
        assertNull(added.left());
        assertEquals("Added", added.right().name());
        assertEquals(
                List.of(
                        "Case.css",
                        "added.html",
                        "case.css",
                        "child.html",
                        "removed.html",
                        "root.html"),
                result.files().getData().stream().map(p -> p.path()).toList());
        assertEquals(Status.removed, result.files().getData().getFirst().status());
        assertNull(result.files().getData().getFirst().right());
        assertEquals("left", result.left().id());
        assertEquals("right", result.right().id());
    }

    @Test
    void pagesMayBeAddedOrRemovedWithoutTheirFilesChangingAndUnicodePathsRemainDistinct()
            throws Exception {
        var files = List.of(file("入口.html"), file("子目录/反馈.html"));
        var left = revision("left", files, List.of(PrototypePageInfo.page("入口", "入口.html")));
        var right = revision("right", files, List.of(PrototypePageInfo.page("反馈", "子目录/反馈.html")));
        var result = PrototypeRevisionComparison.compare(left, right, input());
        assertEquals(new Counts(1, 1, 0, 0), result.summary().pages());
        assertEquals(new Counts(0, 0, 0, 2), result.summary().files());
        assertEquals(0, result.files().getTotal());
        assertEquals("入口.html", result.pages().getData().getFirst().path());
        assertEquals(Status.removed, result.pages().getData().getFirst().status());
    }

    @Test
    void hexadecimalLetterCaseDoesNotChangeTheDigestOrPageContent() throws Exception {
        var pages = List.of(PrototypePageInfo.page("Index", "index.html"));
        var left = revision("left", List.of(file("index.html")), pages);
        var right =
                revision(
                        "right",
                        List.of(
                                new PrototypeManifest.FileEntry(
                                        "index.html",
                                        1,
                                        HASH.toUpperCase(java.util.Locale.ROOT),
                                        "text/html")),
                        pages);
        var result = PrototypeRevisionComparison.compare(left, right, input());
        assertEquals(new Counts(0, 0, 0, 1), result.summary().files());
        assertEquals(new Counts(0, 0, 0, 1), result.summary().pages());
        assertTrue(result.files().getData().isEmpty());
        assertTrue(result.pages().getData().isEmpty());
    }

    @Test
    void preservesGenuineLegacyNullPageIdAndComparesSameRevisionWithoutFabricatingIdentity()
            throws Exception {
        var revision =
                revision(
                        "same",
                        List.of(file("index.html")),
                        List.of(
                                new PrototypePageInfo(
                                        "Legacy", null, "index.html", false, List.of())));
        var input = input();
        var hidden = PrototypeRevisionComparison.compare(revision, revision, input);
        assertEquals(new Counts(0, 0, 0, 1), hidden.summary().pages());
        assertEquals(new Counts(0, 0, 0, 1), hidden.summary().files());
        assertTrue(hidden.pages().getData().isEmpty());
        assertTrue(hidden.files().getData().isEmpty());
        input.setIncludeUnchanged(true);
        var shown = PrototypeRevisionComparison.compare(revision, revision, input);
        assertEquals(1, shown.pages().getTotal());
        assertNull(shown.pages().getData().getFirst().left().pageId());
        assertNull(shown.pages().getData().getFirst().right().pageId());
        assertEquals(Status.unchanged, shown.pages().getData().getFirst().status());
    }

    @Test
    void independentlyPagesMoreThanTwoHundredRowsAfterFilteringAndHandlesMaximumIntegerIndex()
            throws Exception {
        var firstFiles = new ArrayList<PrototypeManifest.FileEntry>();
        var secondFiles = new ArrayList<PrototypeManifest.FileEntry>();
        var pages = new ArrayList<PrototypePageInfo>();
        IntStream.range(0, 403)
                .forEach(
                        i -> {
                            String path = "pages/%03d.html".formatted(i);
                            firstFiles.add(file(path));
                            secondFiles.add(
                                    new PrototypeManifest.FileEntry(
                                            path, 1, i % 2 == 0 ? CHANGED : HASH, "text/html"));
                            pages.add(PrototypePageInfo.page("Page " + i, path));
                        });
        var left = revision("left", firstFiles, pages);
        var right = revision("right", secondFiles, pages);
        var input = input();
        input.setPagesPageIndex(1);
        input.setPagesPageSize(3);
        input.setFilesPageIndex(2);
        input.setFilesPageSize(100);
        var result = PrototypeRevisionComparison.compare(left, right, input);
        assertEquals(new Counts(0, 0, 202, 201), result.summary().files());
        assertEquals(202, result.pages().getTotal());
        assertEquals(202, result.files().getTotal());
        assertEquals(
                List.of("pages/006.html", "pages/008.html", "pages/010.html"),
                result.pages().getData().stream().map(p -> p.path()).toList());
        assertEquals(
                List.of("pages/400.html", "pages/402.html"),
                result.files().getData().stream().map(p -> p.path()).toList());
        assertEquals(1, result.pages().getPageIndex());
        assertEquals(3, result.pages().getPageSize());
        input.setIncludeUnchanged(true);
        input.setPagesPageIndex(Integer.MAX_VALUE);
        input.setFilesPageIndex(Integer.MAX_VALUE);
        result = PrototypeRevisionComparison.compare(left, right, input);
        assertEquals(403, result.files().getTotal());
        assertEquals(403, result.pages().getTotal());
        assertTrue(result.files().getData().isEmpty());
        assertTrue(result.pages().getData().isEmpty());
        assertEquals(Integer.MAX_VALUE, result.files().getPageIndex());
        assertEquals(new Counts(0, 0, 202, 201), result.summary().pages());
    }

    @Test
    void rejectsJsonLiteralNullAsMalformedPersistedManifest() throws Exception {
        var revision = revision("null", List.of(file("index.html")), List.of());
        revision.setManifestJson("null");
        assertInvalid(revision);
    }

    @Test
    void rejectsMalformedPersistedManifestAtItsParsingBoundary() throws Exception {
        assertInvalid(json -> json.put("formatVersion", 2));
        assertInvalid(json -> json.put("entryFile", "missing.html"));
        assertInvalid(
                json -> json.withArray("files").add(json.withArray("files").get(0).deepCopy()));
        assertInvalid(json -> ((ObjectNode) json.withArray("files").get(0)).put("size", -1));
        assertInvalid(json -> ((ObjectNode) json.withArray("files").get(0)).put("sha256", "bad"));
        assertInvalid(
                json -> ((ObjectNode) json.withArray("pages").get(0)).put("path", "foreign.html"));
        assertInvalid(json -> ((ObjectNode) json.withArray("pages").get(0)).put("pageId", "bad"));
        var revision = revision("bad", List.of(file("index.html")), List.of());
        revision.setManifestJson("{");
        assertInvalid(revision);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../index.html",
                "/index.html",
                "a/./index.html",
                "a//index.html",
                "a\\index.html",
                "a/../index.html",
                "C:/index.html",
                "index.html\u0000"
            })
    void rejectsUnsafeManifestFilePathsWithoutConsultingTheDisk(String path) throws Exception {
        assertInvalid(json -> ((ObjectNode) json.withArray("files").get(0)).put("path", path));
    }

    @Test
    void validatesOnlyPublicComparisonInputWithIndependentPagingBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var input = input();
            assertTrue(validator.validate(input).isEmpty());
            input.setLeftRevisionId(" ");
            input.setRightRevisionId(null);
            input.setPagesPageIndex(-1);
            input.setFilesPageIndex(-1);
            input.setPagesPageSize(101);
            input.setFilesPageSize(0);
            assertEquals(6, validator.validate(input).size());
        }
    }

    private void assertInvalid(Consumer<ObjectNode> change) throws Exception {
        var revision =
                revision(
                        "bad",
                        List.of(file("index.html")),
                        List.of(PrototypePageInfo.page("Index", "index.html")));
        ObjectNode json = (ObjectNode) JSON.readTree(revision.getManifestJson());
        change.accept(json);
        revision.setManifestJson(JSON.writeValueAsString(json));
        assertInvalid(revision);
    }

    private void assertInvalid(PrototypeRevisionEntity revision) {
        var error =
                assertThrows(
                        PrototypeHubException.class,
                        () -> PrototypeRevisionComparison.compare(revision, revision, input()));
        assertEquals(400, error.getStatus());
        assertEquals("PROTOTYPE_HUB_MANIFEST_INVALID", error.getCode());
    }

    private static RevisionComparisonRequest input() {
        var input = new RevisionComparisonRequest();
        input.setLeftRevisionId("left");
        input.setRightRevisionId("right");
        return input;
    }

    private static PrototypeManifest.FileEntry file(String path) {
        return new PrototypeManifest.FileEntry(path, 1, HASH, "text/html");
    }

    private static PrototypeRevisionEntity revision(
            String id, List<PrototypeManifest.FileEntry> files, List<PrototypePageInfo> pages)
            throws Exception {
        var revision = new PrototypeRevisionEntity();
        revision.setId(id);
        revision.setProjectId("project");
        revision.setVersionId("version");
        revision.setVersionName(id);
        revision.setEntryFile(files.getFirst().path());
        revision.setFileCount(files.size());
        revision.setTotalBytes((long) files.size());
        revision.setCreateTime(1L);
        revision.setManifestJson(
                JSON.writeValueAsString(
                        new PrototypeManifest(1, files.getFirst().path(), files, pages)));
        return revision;
    }
}
