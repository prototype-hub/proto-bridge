package com.gaiys.proto.prototypehub.storage.assets;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.builder.PrototypeManifestBuilder;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest.FileEntry;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import com.gaiys.proto.prototypehub.web.request.AssetExportRequest;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 仅供FileWork调用的清单校验、原字节复制及有限磁盘打包。 每请求独占Attempt；不访问数据库、不保存权限、不修改源文件，不将导出包聚合到堆内存。 */
@Component
@RequiredArgsConstructor
public class PrototypeAssetFiles {
    private final PrototypeHubStorageProperties properties;
    private final ObjectMapper mapper;

    public static final String ZIP_MANIFEST_PATH = "META-INF/prototype-assets.json";

    public enum Format {
        FILE,
        ZIP,
        MANIFEST
    }

    public record Snapshot(
            String projectId,
            String versionId,
            String revisionId,
            String manifestSha256,
            String storagePath,
            List<FileEntry> assets) {}

    public Snapshot snapshot(PrototypeRevisionEntity revision) throws Exception {
        String json = revision.getManifestJson();
        if (json == null || json.length() > PrototypeManifestBuilder.MAX_MANIFEST_BYTES)
            throw error("MANIFEST_UNAVAILABLE", 409);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        if (jsonBytes.length > PrototypeManifestBuilder.MAX_MANIFEST_BYTES
                || !hash(jsonBytes).equals(revision.getManifestSha256()))
            throw error("MANIFEST_UNAVAILABLE", 409);
        com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest manifest;
        try {
            manifest = PrototypeManifestBuilder.parse(json);
            if (manifest == null || manifest.formatVersion() != 1)
                throw error("MANIFEST_UNAVAILABLE", 409);
        } catch (PrototypeHubException failure) {
            throw error("MANIFEST_UNAVAILABLE", 409);
        }
        var paths = new HashSet<String>();
        var assets = new ArrayList<FileEntry>();
        for (var file : manifest.files()) {
            if (file == null
                    || !canonical(file.path())
                    || !paths.add(file.path())
                    || file.size() < 0
                    || file.sha256() == null
                    || !file.sha256().matches("[a-f0-9]{64}")
                    || file.mediaType() == null) throw error("MANIFEST_UNAVAILABLE", 409);
            if (file.mediaType().startsWith("image/")) assets.add(file);
        }
        assets.sort(Comparator.comparing(FileEntry::path));
        return new Snapshot(
                revision.getProjectId(),
                revision.getVersionId(),
                revision.getId(),
                revision.getManifestSha256(),
                revision.getStoragePath(),
                List.copyOf(assets));
    }

    public List<FileEntry> select(Snapshot snapshot, AssetExportRequest input, Format format) {
        if (!snapshot.manifestSha256().equals(input.getExpectedManifestSha256()))
            throw error("MANIFEST_CONFLICT", 409);
        var requested = input.getPaths();
        if (requested == null
                || requested.size() > 20000
                || (input.isAll() && !requested.isEmpty())
                || (input.isAll() && format == Format.FILE)) throw error("SELECTION_INVALID", 400);
        List<FileEntry> selected;
        if (input.isAll()) selected = snapshot.assets();
        else {
            var names = new HashSet<>(requested);
            if (names.size() != requested.size() || names.contains(null))
                throw error("SELECTION_INVALID", 400);
            selected = snapshot.assets().stream().filter(f -> names.contains(f.path())).toList();
            if (selected.size() != requested.size()) throw error("SELECTION_INVALID", 400);
        }
        if (selected.isEmpty() || (format == Format.FILE && selected.size() != 1))
            throw error("SELECTION_INVALID", 400);
        if (selected.size() > Math.min(20000, properties.getMaxZipFiles())
                || bytes(selected) > properties.getMaxExtractedSizeBytes()) throw limit();
        if (format == Format.FILE
                && selected.getFirst().size() > properties.getMaxUploadSizeBytes()) throw limit();
        return selected;
    }

    public static long bytes(List<FileEntry> entries) {
        try {
            long total = 0;
            for (var entry : entries) total = Math.addExact(total, entry.size());
            return total;
        } catch (ArithmeticException error) {
            throw limit();
        }
    }

    private static boolean canonical(String path) {
        if (path == null
                || path.isBlank()
                || path.length() > 1024
                || path.matches("^[A-Za-z]:.*")
                || path.indexOf('\\') >= 0
                || path.indexOf('\0') >= 0) return false;
        try {
            Path value = Path.of(path);
            return !value.isAbsolute()
                    && !value.startsWith("..")
                    && !path.equals(".")
                    && value.normalize().toString().equals(path);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String hash(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    public Attempt attempt(Snapshot snapshot, List<FileEntry> selected, Format format) {
        return new Attempt(snapshot, selected, format);
    }

    /** 生命周期由usingWhen覆盖到响应结束；取消先置标记，清理与磁盘操作互斥，避免迟到任务重建临时目录。 */
    public final class Attempt {
        private final Snapshot snapshot;
        private final List<FileEntry> selected;
        private final Format format;
        private Path directory;
        private Path output;
        private LimitedOutput sink;
        private ZipOutputStream zip;
        private boolean closed;
        private volatile boolean cancelled;

        private Attempt(Snapshot snapshot, List<FileEntry> selected, Format format) {
            this.snapshot = snapshot;
            this.selected = selected;
            this.format = format;
        }

        public void cancel() {
            cancelled = true;
        }

        public synchronized void open() throws Exception {
            check();
            directory = Files.createTempDirectory("prototypehub-assets-");
            output = directory.resolve("export");
            if (format != Format.MANIFEST) {
                sink =
                        new LimitedOutput(
                                Files.newOutputStream(output), properties.getMaxUploadSizeBytes());
                if (format == Format.ZIP) zip = new ZipOutputStream(sink, StandardCharsets.UTF_8);
            }
        }

        public synchronized void copy(FileEntry entry) throws Exception {
            check();
            Path site =
                    SafePaths.storedVersion(
                                    properties.getRoot(),
                                    snapshot.projectId(),
                                    snapshot.storagePath())
                            .resolve("site");
            Path source;
            try {
                source = SafePaths.within(site, entry.path());
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(source) != entry.size())
                    throw error("CONTENT_UNAVAILABLE", 409);
            } catch (IOException | PrototypeHubException failure) {
                throw error("CONTENT_UNAVAILABLE", 409);
            }
            if (zip != null) {
                var item = new ZipEntry(entry.path());
                item.setTime(0);
                zip.putNextEntry(item);
            }
            var digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var input =
                    Files.newInputStream(
                            source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    check();
                    size += read;
                    if (size > entry.size()) throw error("CONTENT_UNAVAILABLE", 409);
                    digest.update(buffer, 0, read);
                    if (zip != null) zip.write(buffer, 0, read);
                    else if (sink != null) sink.write(buffer, 0, read);
                }
            } catch (IOException failure) {
                throw error("CONTENT_UNAVAILABLE", 409);
            }
            if (size != entry.size()
                    || !HexFormat.of().formatHex(digest.digest()).equals(entry.sha256()))
                throw error("CONTENT_UNAVAILABLE", 409);
            if (zip != null) zip.closeEntry();
        }

        public synchronized Path finish() throws Exception {
            check();
            if (zip != null) {
                var item = new ZipEntry(ZIP_MANIFEST_PATH);
                item.setTime(0);
                zip.putNextEntry(item);
                writeManifest(zip);
                zip.closeEntry();
                zip.close();
                zip = null;
                sink = null;
            } else if (sink != null) {
                sink.close();
                sink = null;
            }
            if (format == Format.MANIFEST) {
                try (var json =
                        new LimitedOutput(
                                Files.newOutputStream(output),
                                PrototypeManifestBuilder.MAX_MANIFEST_BYTES)) {
                    writeManifest(json);
                }
            }
            return output;
        }

        private void writeManifest(OutputStream output) throws IOException {
            // writer不关闭外层ZIP；JSON及整个压缩输出各自受既有预算约束。
            mapper.writer()
                    .without(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                    .writeValue(
                            new LimitedOutput(output, PrototypeManifestBuilder.MAX_MANIFEST_BYTES),
                            Map.of(
                                    "formatVersion",
                                    1,
                                    "projectId",
                                    snapshot.projectId(),
                                    "versionId",
                                    snapshot.versionId(),
                                    "revisionId",
                                    snapshot.revisionId(),
                                    "manifestSha256",
                                    snapshot.manifestSha256(),
                                    "total",
                                    selected.size(),
                                    "totalBytes",
                                    bytes(selected),
                                    "files",
                                    selected));
        }

        public synchronized void close() throws Exception {
            if (closed) return;
            closed = true;
            cancelled = true;
            // 失败或取消时只释放ZIP/deflater，尾记录丢弃，不能重触发预算错误掩盖原始失败。
            if (sink != null) sink.discard = true;
            try {
                if (zip != null) zip.close();
                else if (sink != null) sink.close();
            } finally {
                if (directory != null) SecureZipExtractor.deleteRecursively(directory);
            }
        }

        private void check() {
            if (closed || cancelled) throw new CancellationException("Asset export cancelled");
        }
    }

    private static final class LimitedOutput extends FilterOutputStream {
        private final long maximum;
        private long count;
        private boolean discard;

        private LimitedOutput(OutputStream output, long maximum) {
            super(output);
            this.maximum = maximum;
        }

        @Override
        public void write(int value) throws IOException {
            if (!discard) {
                ensure(1);
                out.write(value);
            }
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            if (!discard) {
                ensure(length);
                out.write(value, offset, length);
            }
        }

        private void ensure(int length) {
            if (length > maximum - count) throw limit();
            count += length;
        }
    }

    public static PrototypeHubException error(String suffix, int status) {
        return new PrototypeHubException(
                "error.prototype-hub.asset_" + suffix.toLowerCase(java.util.Locale.ROOT),
                "ASSET_" + suffix,
                status);
    }

    private static PrototypeHubException limit() {
        return error("EXPORT_LIMIT_EXCEEDED", 413);
    }
}
