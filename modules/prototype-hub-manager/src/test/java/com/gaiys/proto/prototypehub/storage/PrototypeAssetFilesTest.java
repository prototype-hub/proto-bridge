package com.gaiys.proto.prototypehub.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.assets.PrototypeAssetFiles;
import com.gaiys.proto.prototypehub.storage.assets.PrototypeAssetFiles.Format;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest.FileEntry;
import com.gaiys.proto.prototypehub.web.request.AssetExportRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrototypeAssetFilesTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PrototypeHubStorageProperties properties = new PrototypeHubStorageProperties();

    @Test
    void immutableManifestFiltersImagesAndUsesOriginalLiteralPathOrder() throws Exception {
        var files = files();
        var snapshot =
                files.snapshot(
                        revision(
                                List.of(
                                        entry("中/图.svg", "<svg/>", "image/svg+xml"),
                                        entry("a/logo.png", "png", "image/png"),
                                        entry("A/logo.png", "PNG", "image/png"),
                                        entry("index.html", "home", "text/html"))));
        assertEquals("old-rid", snapshot.revisionId());
        assertEquals(
                List.of("A/logo.png", "a/logo.png", "中/图.svg"),
                snapshot.assets().stream().map(FileEntry::path).toList());
        assertEquals(12, PrototypeAssetFiles.bytes(snapshot.assets()));
        assertEquals(
                snapshot.assets(),
                files.select(snapshot, request(snapshot.manifestSha256(), true), Format.ZIP));
        assertEquals(
                List.of(snapshot.assets().get(0), snapshot.assets().get(2)),
                files.select(
                        snapshot,
                        request(snapshot.manifestSha256(), false, "中/图.svg", "A/logo.png"),
                        Format.ZIP));
    }

    @Test
    void storedCorruptOrTraversalManifestIsUnavailableBeforeAnyFileRead() throws Exception {
        var files = files();
        for (String path :
                List.of(
                        "../x.svg",
                        "a/../../x.svg",
                        "a/../x.svg",
                        "/x.svg",
                        "C:/x.svg",
                        "a\\x.svg",
                        "."))
            code(
                    "ASSET_MANIFEST_UNAVAILABLE",
                    () -> files.snapshot(revision(List.of(entry(path, "x", "image/svg+xml")))));
        var row = revision(List.of(entry("a.svg", "x", "image/svg+xml")));
        row.setManifestSha256("0".repeat(64));
        code("ASSET_MANIFEST_UNAVAILABLE", () -> files.snapshot(row));
        row.setManifestJson("not-json");
        row.setManifestSha256(hash(row.getManifestJson().getBytes(StandardCharsets.UTF_8)));
        code("ASSET_MANIFEST_UNAVAILABLE", () -> files.snapshot(row));
        code(
                "ASSET_MANIFEST_UNAVAILABLE",
                () ->
                        files.snapshot(
                                revision(
                                        List.of(
                                                entry("a.svg", "x", "image/svg+xml"),
                                                entry("a.svg", "y", "image/svg+xml")))));
    }

    @Test
    void nullOrOmittedManifestCollectionsAndNullRootUseTheUnavailableContract() throws Exception {
        var files = files();
        var row = revision(List.of());
        for (String json :
                List.of(
                        "null",
                        "{\"formatVersion\":1,\"entryFile\":\"index.html\",\"files\":null,\"pages\":[]}",
                        "{\"formatVersion\":1,\"entryFile\":\"index.html\",\"pages\":[]}",
                        "{\"formatVersion\":1,\"entryFile\":\"index.html\",\"files\":[],\"pages\":null}")) {
            row.setManifestJson(json);
            row.setManifestSha256(hash(json.getBytes(StandardCharsets.UTF_8)));
            code("ASSET_MANIFEST_UNAVAILABLE", () -> files.snapshot(row));
        }
    }

    @Test
    void exactSelectionRejectsUrlsAliasesDuplicatesAllConflictsAndBudgetOverflow()
            throws Exception {
        var files = files();
        var snapshot =
                files.snapshot(
                        revision(
                                List.of(
                                        entry("a.svg", "xx", "image/svg+xml"),
                                        entry("b.svg", "yy", "image/svg+xml"))));
        for (var input :
                List.of(
                        request(snapshot.manifestSha256(), false),
                        request(snapshot.manifestSha256(), false, "../a.svg"),
                        request(snapshot.manifestSha256(), false, "https://example.com/a.svg"),
                        request(snapshot.manifestSha256(), false, "a.svg", "a.svg"),
                        request(snapshot.manifestSha256(), true, "a.svg")))
            code("ASSET_SELECTION_INVALID", () -> files.select(snapshot, input, Format.ZIP));
        code(
                "ASSET_SELECTION_INVALID",
                () ->
                        files.select(
                                snapshot, request(snapshot.manifestSha256(), true), Format.FILE));
        code(
                "ASSET_SELECTION_INVALID",
                () ->
                        files.select(
                                snapshot,
                                request(snapshot.manifestSha256(), false, "a.svg", "b.svg"),
                                Format.FILE));
        code(
                "ASSET_MANIFEST_CONFLICT",
                () -> files.select(snapshot, request("0".repeat(64), false, "a.svg"), Format.FILE));
        properties.setMaxZipFiles(1);
        code(
                "ASSET_EXPORT_LIMIT_EXCEEDED",
                () -> files.select(snapshot, request(snapshot.manifestSha256(), true), Format.ZIP));
        properties.setMaxZipFiles(20000);
        properties.setMaxExtractedSizeMb(0);
        code(
                "ASSET_EXPORT_LIMIT_EXCEEDED",
                () ->
                        files.select(
                                snapshot,
                                request(snapshot.manifestSha256(), true),
                                Format.MANIFEST));
        properties.setMaxExtractedSizeMb(2048);
        properties.setMaxUploadSizeMb(0);
        code(
                "ASSET_EXPORT_LIMIT_EXCEEDED",
                () ->
                        files.select(
                                snapshot,
                                request(snapshot.manifestSha256(), false, "a.svg"),
                                Format.FILE));
        assertThrows(
                PrototypeHubException.class,
                () ->
                        PrototypeAssetFiles.bytes(
                                List.of(
                                        new FileEntry("a", Long.MAX_VALUE, "", ""),
                                        new FileEntry("b", 1, "", ""))));
    }

    @Test
    void zipAndJsonRetainUnicodeRelativePathsOriginalBytesAndMatchingHashesUntilClose()
            throws Exception {
        var files = files();
        var entries =
                List.of(
                        entry("一/logo.svg", "<svg>原图</svg>", "image/svg+xml"),
                        entry("二/logo.svg", "<svg>另一图</svg>", "image/svg+xml"));
        write("一/logo.svg", "<svg>原图</svg>");
        write("二/logo.svg", "<svg>另一图</svg>");
        var snapshot = files.snapshot(revision(entries));
        for (var format : Format.values()) {
            var selected = format == Format.FILE ? List.of(entries.getFirst()) : entries;
            var attempt = files.attempt(snapshot, selected, format);
            Path output;
            try {
                attempt.open();
                for (var entry : selected) attempt.copy(entry);
                output = attempt.finish();
                assertTrue(Files.exists(output));
                if (format == Format.FILE) assertEquals("<svg>原图</svg>", Files.readString(output));
                else if (format == Format.ZIP) {
                    try (var zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
                        assertEquals(3, zip.size());
                        for (var entry : entries)
                            assertEquals(
                                    entry.sha256(),
                                    hash(
                                            zip.getInputStream(zip.getEntry(entry.path()))
                                                    .readAllBytes()));
                        var json =
                                mapper.readTree(
                                        zip.getInputStream(
                                                zip.getEntry(
                                                        PrototypeAssetFiles.ZIP_MANIFEST_PATH)));
                        assertEquals(
                                mapper.readTree(mapper.writeValueAsString(entries)),
                                json.get("files"));
                        assertEquals(
                                snapshot.manifestSha256(), json.get("manifestSha256").asText());
                    }
                } else {
                    var json = mapper.readTree(output.toFile());
                    assertEquals(
                            mapper.readTree(mapper.writeValueAsString(entries)), json.get("files"));
                    assertEquals("old-rid", json.get("revisionId").asText());
                    assertEquals(
                            PrototypeAssetFiles.bytes(entries), json.get("totalBytes").asLong());
                }
            } finally {
                attempt.close();
            }
            assertFalse(Files.exists(output.getParent()));
        }
    }

    @Test
    void missingSizeHashAndSymlinkFailWithoutChangingSourceAndZipOutputIsBounded()
            throws Exception {
        var files = files();
        var entry = entry("a.svg", "correct", "image/svg+xml");
        var snapshot = files.snapshot(revision(List.of(entry)));
        for (String content : List.of("missing", "short", "wrong!!", "symlink")) {
            Path source = root.resolve("projects/p/versions/storage/site/a.svg");
            Files.createDirectories(source.getParent());
            Files.deleteIfExists(source);
            if (content.equals("symlink")) {
                Files.writeString(root.resolve("outside"), "correct");
                Files.createSymbolicLink(source, root.resolve("outside"));
            } else if (!content.equals("missing")) Files.writeString(source, content);
            var attempt = files.attempt(snapshot, List.of(entry), Format.ZIP);
            try {
                attempt.open();
                code("ASSET_CONTENT_UNAVAILABLE", () -> attempt.copy(entry));
            } finally {
                attempt.close();
            }
            if (content.equals("wrong!!")) assertEquals(content, Files.readString(source));
        }
        Files.deleteIfExists(root.resolve("projects/p/versions/storage/site/a.svg"));
        write("a.svg", "correct");
        properties.setMaxUploadSizeMb(0);
        var attempt = files.attempt(snapshot, List.of(entry), Format.ZIP);
        try {
            attempt.open();
            code("ASSET_EXPORT_LIMIT_EXCEEDED", () -> attempt.copy(entry));
        } finally {
            attempt.close();
        }
    }

    @Test
    void cancellationBeforeQueuedOpenOrAfterPreparationCannotRecreateDeletedTemp()
            throws Exception {
        var files = files();
        var entry = entry("a.svg", "x", "image/svg+xml");
        var snapshot = files.snapshot(revision(List.of(entry)));
        write("a.svg", "x");
        var before = files.attempt(snapshot, List.of(entry), Format.FILE);
        before.cancel();
        before.close();
        assertThrows(CancellationException.class, before::open);
        var during = files.attempt(snapshot, List.of(entry), Format.FILE);
        during.open();
        during.copy(entry);
        Path output = during.finish();
        during.cancel();
        during.close();
        assertFalse(Files.exists(output.getParent()));
        assertThrows(CancellationException.class, () -> during.copy(entry));
        assertThrows(CancellationException.class, during::open);
    }

    private PrototypeAssetFiles files() {
        properties.setRoot(root);
        return new PrototypeAssetFiles(properties, mapper);
    }

    private void write(String path, String content) throws Exception {
        Path file = root.resolve("projects/p/versions/storage/site").resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private PrototypeRevisionEntity revision(List<FileEntry> entries) throws Exception {
        var row = new PrototypeRevisionEntity();
        row.setId("old-rid");
        row.setVersionId("v");
        row.setProjectId("p");
        row.setStoragePath("projects/p/versions/storage/site");
        row.setManifestJson(
                mapper.writeValueAsString(
                        new PrototypeManifest(1, "index.html", entries, List.of())));
        row.setManifestSha256(hash(row.getManifestJson().getBytes(StandardCharsets.UTF_8)));
        return row;
    }

    private static FileEntry entry(String path, String content, String type) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new FileEntry(path, bytes.length, hash(bytes), type);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static AssetExportRequest request(String hash, boolean all, String... paths) {
        var input = new AssetExportRequest();
        input.setExpectedManifestSha256(hash);
        input.setAll(all);
        input.setPaths(List.of(paths));
        return input;
    }

    private static void code(String expected, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(expected, assertThrows(PrototypeHubException.class, operation).getCode());
    }
}
