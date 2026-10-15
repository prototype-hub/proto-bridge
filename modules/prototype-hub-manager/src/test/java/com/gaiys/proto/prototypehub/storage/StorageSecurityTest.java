package com.gaiys.proto.prototypehub.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.injector.HtmlInspectorInjector;
import com.gaiys.proto.prototypehub.storage.parser.AxureSitemapParser;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageSecurityTest {
    @TempDir Path root;
    final PrototypeHubStorageProperties properties = new PrototypeHubStorageProperties();

    Path archive(Map<String, String> entries) throws Exception {
        Path path = root.resolve(UUID.randomUUID() + ".zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }

    String extract(Map<String, String> entries) throws Exception {
        return new SecureZipExtractor(properties, new HtmlInspectorInjector(properties))
                .extract(archive(entries), root.resolve(UUID.randomUUID().toString()));
    }

    @Test
    void validNestedAssetsAndEntryOrdering() throws Exception {
        assertEquals(
                "site/index.html",
                extract(
                        Map.of(
                                "site/index.html",
                                "<body>home</body>",
                                "start.html",
                                "start",
                                "site/images/a.svg",
                                "<svg/>",
                                "site/css/style.css",
                                "body{}",
                                "site/js/app.js",
                                "window.x=1")));
        assertEquals("start.html", extract(Map.of("other.html", "other", "start.html", "start")));
    }

    @Test
    void traversalAbsoluteHiddenAndDangerousExtensionsAreRejected() {
        for (String name :
                List.of(
                        "../escape.html",
                        "/absolute.html",
                        "a/../../escape.html",
                        "a\\b.html",
                        "C:/a.html",
                        ".secret",
                        "a/.hidden",
                        "x.PHP",
                        "run.exe",
                        ".htaccess"))
            assertThrows(
                    PrototypeHubException.class,
                    () -> extract(Map.of("index.html", "ok", name, "bad")),
                    name);
        assertFalse(Files.exists(root.getParent().resolve("escape.html")));
    }

    @Test
    void compressedExpandedCountDepthAndHtmlLimits() throws Exception {
        properties.setMaxUploadSizeMb(0);
        assertThrows(PrototypeHubException.class, () -> extract(Map.of("index.html", "a")));
        properties.setMaxUploadSizeMb(500);
        properties.setMaxExtractedSizeMb(0);
        assertThrows(PrototypeHubException.class, () -> extract(Map.of("index.html", "a")));
        properties.setMaxExtractedSizeMb(2048);
        properties.setMaxZipFiles(1);
        assertThrows(
                PrototypeHubException.class,
                () -> extract(Map.of("index.html", "a", "asset.txt", "b")));
        properties.setMaxZipFiles(20000);
        properties.setMaxEntrySearchDepth(1);
        assertThrows(PrototypeHubException.class, () -> extract(Map.of("nested/index.html", "a")));
        properties.setMaxEntrySearchDepth(6);
        properties.setMaxHtmlSizeMb(0);
        assertThrows(PrototypeHubException.class, () -> extract(Map.of("index.html", "a")));
    }

    @Test
    void malformedArchiveAndMissingHtmlAreClientErrors() throws Exception {
        Path bad = root.resolve("bad.zip");
        Files.writeString(bad, "not a zip");
        assertThrows(
                PrototypeHubException.class,
                () ->
                        new SecureZipExtractor(properties, new HtmlInspectorInjector(properties))
                                .extract(bad, root.resolve("bad-site")));
        assertThrows(PrototypeHubException.class, () -> extract(Map.of("image.svg", "<svg/>")));
    }

    @Test
    void zipSymlinksAndDuplicateNormalizedEntriesAreRejected() throws Exception {
        Path zip = root.resolve("symlink.zip");
        try (var out = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(zip)) {
            var link = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("index.html");
            link.setUnixMode(0120777);
            out.putArchiveEntry(link);
            out.write("../../secret".getBytes());
            out.closeArchiveEntry();
        }
        var extractor = new SecureZipExtractor(properties, new HtmlInspectorInjector(properties));
        assertThrows(
                PrototypeHubException.class, () -> extractor.extract(zip, root.resolve("links")));
        assertThrows(
                PrototypeHubException.class,
                () -> extract(Map.of("a/index.html", "a", "a//index.html", "b")));
    }

    @Test
    void boundaryChecksAndSymlinkDeletionPreserveNeighbor() throws Exception {
        properties.setRoot(root);
        Path neighbor = root.resolve("neighbor");
        Files.createDirectory(neighbor);
        Files.writeString(neighbor.resolve("keep"), "safe");
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, neighbor);
        for (String path : List.of("../outside", "/absolute", "a\\b", "link/keep"))
            assertThrows(PrototypeHubException.class, () -> SafePaths.within(root, path));
        assertThrows(
                PrototypeHubException.class,
                () -> SafePaths.storedVersion(root, "p", "projects/q/versions/v/site"));
        assertThrows(PrototypeHubException.class, () -> SafePaths.id("../bad"));
        assertEquals(
                root.resolve("projects/p/versions/v"),
                SafePaths.storedVersion(root, "p", "projects/p/versions/v/site"));
        SecureZipExtractor.deleteRecursively(link);
        SecureZipExtractor.deleteRecursively(link);
        assertTrue(Files.exists(neighbor.resolve("keep")));
        SecureZipExtractor.deleteRecursively(neighbor);
        assertFalse(Files.exists(neighbor));
    }

    @Test
    void inspectorInjectionIsIdempotentAndValidatesUrl() throws Exception {
        Path html = root.resolve("index.html");
        Files.writeString(html, "<BODY>text</BODY>");
        var injector = new HtmlInspectorInjector(properties);
        injector.inject(html);
        String first = Files.readString(html);
        injector.inject(html);
        assertEquals(first, Files.readString(html));
        assertTrue(first.indexOf("<script") < first.indexOf("</BODY>"));
        Path fragment = root.resolve("a.htm");
        Files.writeString(fragment, "fragment");
        injector.inject(fragment);
        assertTrue(Files.readString(fragment).contains("data-prototype-hub-inspector"));
        assertTrue(first.contains("/api/prototype-hub/public/inspector.js"));
        Path legacy = root.resolve("legacy.html");
        String legacyHtml =
                "<html><script data-protohub-inspector src=\"/api/protohub/public/inspector.js\"></script></html>";
        Files.writeString(legacy, legacyHtml);
        injector.inject(legacy);
        assertEquals(legacyHtml, Files.readString(legacy));
        properties.setInspectorPath("/bad\"url");
        Path invalid = root.resolve("b.html");
        Files.writeString(invalid, "body");
        assertThrows(IllegalStateException.class, () -> injector.inject(invalid));
    }

    @Test
    void sitemapLiteralsNestedFoldersAndMissingPages() throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("index.html"), "<title>Fallback</title>");
        Files.writeString(
                root.resolve("data/document.js"),
                "var rootNodes=[{type:'Folder',pageName:'目录',children:[{type:'Wireframe',pageName:'首页',url:'index.html'},{pageName:'不存在',url:'missing.html'},{pageName:'越界',url:'../../evil.html'}]}];");
        var parsed = new AxureSitemapParser().parse(root, 6);
        assertEquals("目录", parsed.getFirst().name());
        assertEquals("首页", parsed.getFirst().children().getFirst().name());
        assertEquals(1, parsed.getFirst().children().size());
        Files.writeString(
                root.resolve("data/document.js"),
                "var a='rootNodes',b='pageName',c='url'; var data=_(a,[_(b,'别名页面',c,'index.html')]);");
        assertEquals("别名页面", new AxureSitemapParser().parse(root, 6).getFirst().name());
    }

    @Test
    void malformedSitemapFallsBackWithoutExecutingScript() throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve("nested/page.htm"), "<title>Nested page</title>");
        Files.writeString(root.resolve("data/document.js"), "var rootNodes=[process.exit()];");
        var parsed = new AxureSitemapParser().parse(root, 6);
        assertTrue(parsed.getFirst().folder());
        assertEquals("Nested page", parsed.getFirst().children().getFirst().name());
        Files.writeString(root.resolve("data/document.js"), "var rootNodes=[");
        assertEquals(1, new AxureSitemapParser().parse(root, 6).size());
    }
}
