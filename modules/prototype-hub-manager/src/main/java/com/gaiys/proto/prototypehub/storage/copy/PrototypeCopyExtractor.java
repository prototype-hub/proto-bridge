package com.gaiys.proto.prototypehub.storage.copy;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.builder.PrototypeManifestBuilder;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest.FileEntry;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse.Occurrence;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Component;

/** 只由FileWork调用；校验固定源文件、提取静态字面量，不执行脚本、请求网络或修改源文件。 */
@Component
@RequiredArgsConstructor
public class PrototypeCopyExtractor {
    public static final int SOURCE_BYTES = 2 * 1024 * 1024;
    public static final int STATIC_ENTRIES = 2000;
    public static final int STATIC_OCCURRENCES = 10000;

    /** 去重后全部静态原文的UTF16长度总和；单条也不能超过该总额，不截断。 */
    public static final int STATIC_TEXT = 2 * 1024 * 1024;

    private final PrototypeHubStorageProperties properties;

    public record Source(
            String projectId,
            String versionId,
            String revisionId,
            String pageId,
            String pagePath,
            String manifestSha256,
            FileEntry file,
            String storagePath) {}

    public record Literal(String text, List<Occurrence> occurrences) {}

    public record Extracted(String state, String limitReason, List<Literal> literals) {}

    public Source source(PrototypeRevisionEntity revision, String pageId) throws Exception {
        String json = revision.getManifestJson();
        if (json == null || json.length() > PrototypeManifestBuilder.MAX_MANIFEST_BYTES)
            throw error("MANIFEST_INVALID", 409);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > PrototypeManifestBuilder.MAX_MANIFEST_BYTES
                || !hash(bytes).equals(revision.getManifestSha256()))
            throw error("MANIFEST_INVALID", 409);
        var manifest = PrototypeManifestBuilder.parse(json);
        if (manifest == null || manifest.formatVersion() != 1) throw error("MANIFEST_INVALID", 409);
        var matches = new ArrayList<PrototypePageInfo>();
        pages(manifest.pages(), pageId, matches);
        if (matches.size() != 1) throw error("PAGE_INVALID", 404);
        String path = matches.getFirst().path();
        if (!canonical(path)) throw error("MANIFEST_INVALID", 409);
        var files =
                manifest.files().stream().filter(f -> f != null && path.equals(f.path())).toList();
        if (files.size() != 1) throw error("MANIFEST_INVALID", 409);
        var file = files.getFirst();
        if (file.size() < 0
                || file.sha256() == null
                || !file.sha256().matches("[a-f0-9]{64}")
                || file.mediaType() == null
                || !file.mediaType().split(";", 2)[0].trim().equalsIgnoreCase("text/html"))
            throw error("MANIFEST_INVALID", 409);
        return new Source(
                revision.getProjectId(),
                revision.getVersionId(),
                revision.getId(),
                pageId,
                path,
                revision.getManifestSha256(),
                file,
                revision.getStoragePath());
    }

    private static void pages(
            List<PrototypePageInfo> pages, String id, List<PrototypePageInfo> found) {
        for (var page : pages) {
            if (!page.folder() && id.equals(page.pageId())) found.add(page);
            pages(page.children(), id, found);
        }
    }

    public Extracted extract(Source source) throws Exception {
        Path site =
                SafePaths.storedVersion(
                                properties.getRoot(), source.projectId(), source.storagePath())
                        .resolve("site");
        Path path = SafePaths.within(site, source.pagePath());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) != source.file().size()) throw error("SOURCE_CHANGED", 409);
        // 超大源只验证大小及清单身份，不声称已完整读取、散列或解析。
        if (source.file().size() > SOURCE_BYTES) return limited("SOURCE_BYTES");
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(SOURCE_BYTES + 1);
        }
        if (bytes.length != source.file().size() || !hash(bytes).equals(source.file().sha256()))
            throw error("SOURCE_CHANGED", 409);
        return literals(bytes);
    }

    public static Extracted literals(byte[] bytes) throws Exception {
        if (bytes.length > SOURCE_BYTES) return limited("SOURCE_BYTES");
        // InputStream/null charset沿质量检查：BOM/meta声明优先，否则UTF8。Jsoup.parse不加载资源。
        var document =
                Jsoup.parse(
                        new ByteArrayInputStream(bytes), null, "https://prototype-hub.invalid/");
        document.select("script,style,template,noscript,textarea").remove();
        var collector = new Collector();
        try {
            collector.add(document.title(), "DOCUMENT_TITLE");
            NodeTraversor.traverse(
                    new NodeVisitor() {
                        @Override
                        public void head(Node node, int depth) {
                            if (node instanceof TextNode text)
                                collector.add(text.getWholeText(), "TEXT");
                            if (node instanceof Element element) {
                                for (String attribute :
                                        List.of("alt", "title", "placeholder", "aria-label"))
                                    if (element.hasAttr(attribute))
                                        collector.add(
                                                element.attr(attribute),
                                                attribute
                                                        .toUpperCase(java.util.Locale.ROOT)
                                                        .replace('-', '_'));
                                if (element.normalName().equals("input")
                                        && List.of("button", "submit", "reset")
                                                .contains(
                                                        element.attr("type")
                                                                .toLowerCase(
                                                                        java.util.Locale.ROOT)))
                                    collector.add(element.attr("value"), "BUTTON_VALUE");
                            }
                        }

                        @Override
                        public void tail(Node node, int depth) {}
                    },
                    document.body());
            var result =
                    collector.values.entrySet().stream()
                            .map(
                                    entry ->
                                            new Literal(
                                                    entry.getKey(), List.copyOf(entry.getValue())))
                            .toList();
            return new Extracted(result.isEmpty() ? "EMPTY" : "READY", null, result);
        } catch (Budget limit) {
            return limited(limit.reason);
        }
    }

    private static final class Collector {
        final LinkedHashMap<String, List<Occurrence>> values = new LinkedHashMap<>();
        int occurrences;
        int textUnits;

        void add(String original, String kind) {
            String text = normalize(original);
            if (text.isEmpty()) return;
            if (++occurrences > STATIC_OCCURRENCES) throw new Budget("STATIC_OCCURRENCES");
            var positions = values.get(text);
            if (positions == null) {
                if (values.size() == STATIC_ENTRIES) throw new Budget("STATIC_ENTRIES");
                if (text.length() > STATIC_TEXT - textUnits) throw new Budget("STATIC_TEXT");
                textUnits += text.length();
                positions = new ArrayList<>();
                values.put(text, positions);
            }
            positions.add(new Occurrence(occurrences, kind));
        }
    }

    static String normalize(String value) {
        var result = new StringBuilder();
        boolean space = false;
        for (int offset = 0; offset < value.length(); ) {
            int code = value.codePointAt(offset);
            offset += Character.charCount(code);
            if (Character.isWhitespace(code) || Character.isSpaceChar(code))
                space = !result.isEmpty();
            else {
                if (space) result.append(' ');
                result.appendCodePoint(code == 0 ? 0xfffd : code);
                space = false;
            }
        }
        return result.toString();
    }

    private static Extracted limited(String reason) {
        return new Extracted("LIMIT_EXCEEDED", reason, List.of());
    }

    private static final class Budget extends RuntimeException {
        final String reason;

        Budget(String reason) {
            this.reason = reason;
        }
    }

    private static boolean canonical(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 1024
                || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0
                || value.chars().anyMatch(c -> c < 32)) return false;
        for (String part : value.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }

    public static String digest(String... fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static PrototypeHubException error(String code, int status) {
        return new PrototypeHubException(
                "error.prototype-hub.copy_inventory_" + code.toLowerCase(java.util.Locale.ROOT),
                "PROTOTYPE_HUB_COPY_INVENTORY_" + code,
                status);
    }
}
