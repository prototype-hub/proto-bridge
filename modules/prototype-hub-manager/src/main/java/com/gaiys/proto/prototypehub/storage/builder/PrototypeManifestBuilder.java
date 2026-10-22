package com.gaiys.proto.prototypehub.storage.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest;
import com.gaiys.proto.prototypehub.storage.parser.AxureSitemapParser;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Component;

/** 仅在 FileWork 内调用：校验后对已注入的实际 site 字节建立有界、可复现的清单。 */
@Component
@RequiredArgsConstructor
public class PrototypeManifestBuilder {
    public static final int MAX_MANIFEST_BYTES = 32 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PrototypeHubStorageProperties properties;
    private final AxureSitemapParser parser;

    public Built build(Path site, String entry) throws Exception {
        SafePaths.within(site, entry);
        var files = new ArrayList<PrototypeManifest.FileEntry>();
        long total = 0;
        // 文件额度与目录枚举预算分开，避免合法 ZIP 的隐式父目录占用文件名额。
        // 独立节点预算同时约束 legacy 深目录扫描；遍历时不聚合整个目录树。
        long nodes = 0;
        try (var walk = Files.walk(site)) {
            var iterator = walk.iterator();
            while (iterator.hasNext()) {
                Path candidate = iterator.next();
                if (++nodes > (long) properties.getMaxZipFiles() * 8 + 1) throw invalid();
                if (candidate.equals(site)) continue;
                String relative = site.relativize(candidate).toString().replace('\\', '/');
                Path safe = SafePaths.within(site, relative);
                if (relative.length() > 1024 || Files.isSymbolicLink(safe)) throw invalid();
                if (Files.isDirectory(safe, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(safe, LinkOption.NOFOLLOW_LINKS)
                        || files.size() >= properties.getMaxZipFiles()) throw invalid();
                long remaining = properties.getMaxExtractedSizeBytes() - total;
                if (Files.size(safe) > remaining) throw invalid();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long size = 0;
                try (var input = new DigestInputStream(Files.newInputStream(safe), digest)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        size = Math.addExact(size, read);
                        if (size > remaining) throw invalid();
                    }
                }
                total = Math.addExact(total, size);
                files.add(
                        new PrototypeManifest.FileEntry(
                                relative,
                                size,
                                HexFormat.of().formatHex(digest.digest()),
                                MediaTypeFactory.getMediaType(safe.getFileName().toString())
                                        .orElse(MediaType.APPLICATION_OCTET_STREAM)
                                        .toString()));
            }
        }
        files.sort(Comparator.comparing(PrototypeManifest.FileEntry::path));
        if (files.stream().noneMatch(file -> file.path().equals(entry))) throw invalid();
        PrototypeManifest manifest =
                new PrototypeManifest(
                        1, entry, files, parser.parse(site, properties.getMaxEntrySearchDepth()));
        byte[] bytes = JSON.writeValueAsBytes(manifest);
        if (bytes.length > MAX_MANIFEST_BYTES) throw invalid();
        return new Built(
                new String(bytes, StandardCharsets.UTF_8),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                files.size(),
                total);
    }

    public static PrototypeManifest parse(String json) {
        try {
            if (json == null || json.length() > MAX_MANIFEST_BYTES) throw invalid();
            return JSON.readValue(json, PrototypeManifest.class);
        } catch (IOException error) {
            throw invalid();
        }
    }

    private static PrototypeHubException invalid() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.manifest_invalid", "PROTOTYPE_HUB_MANIFEST_INVALID");
    }

    public record Built(String json, String sha256, int fileCount, long totalBytes) {}
}
