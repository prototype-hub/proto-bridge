package com.gaiys.proto.prototypehub.storage.extractor;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.injector.HtmlInspectorInjector;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import lombok.AllArgsConstructor;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class SecureZipExtractor {
    private static final Set<String> FORBIDDEN =
            Set.of(
                    "php",
                    "php3",
                    "php5",
                    "phtml",
                    "jsp",
                    "jspx",
                    "asp",
                    "aspx",
                    "cgi",
                    "exe",
                    "dll",
                    "sh",
                    "bat",
                    "cmd",
                    "jar",
                    "war",
                    "py",
                    "pl",
                    "rb",
                    "htaccess");
    private final PrototypeHubStorageProperties properties;
    private final HtmlInspectorInjector inspector;

    public String extract(Path archive, Path destination) throws IOException {
        if (Files.size(archive) > properties.getMaxUploadSizeBytes()) reject("zip_too_large");
        Files.createDirectory(destination);
        long extracted = 0;
        int files = 0;
        Set<Path> seen = new HashSet<>();
        ArrayList<Path> html = new ArrayList<>();
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (++files > properties.getMaxZipFiles()) reject("zip_file_limit");
                String name = entry.getName();
                byte[] rawName = entry.getRawName();
                if (rawName != null)
                    for (byte b : rawName) if (b == 92) reject("zip_entry_invalid");
                if (entry.isUnixSymlink()
                        || entry.getGeneralPurposeBit().usesEncryption()
                        || !zip.canReadEntryData(entry)) reject("zip_entry_invalid");
                if (name.startsWith("/")
                        || name.contains("\\")
                        || name.indexOf(':') >= 0
                        || name.chars().anyMatch(c -> c < 32)) reject("zip_entry_invalid");
                for (String part : name.split("/"))
                    if (part.equals("..") || part.equals(".") || part.startsWith("."))
                        reject("zip_entry_invalid");
                Path output = SafePaths.within(destination, name);
                if (!seen.add(output)) reject("zip_entry_invalid");
                String lower = name.toLowerCase(Locale.ROOT);
                String extension = lower.substring(lower.lastIndexOf('.') + 1);
                if (FORBIDDEN.contains(extension)) reject("file_type_denied");
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                Files.createDirectories(output.getParent());
                CRC32 crc = new CRC32();
                try (InputStream input = zip.getInputStream(entry);
                        OutputStream target =
                                Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        extracted = Math.addExact(extracted, read);
                        if (extracted > properties.getMaxExtractedSizeBytes()) reject("zip_bomb");
                        crc.update(buffer, 0, read);
                        target.write(buffer, 0, read);
                    }
                }
                if (entry.getCrc() != -1 && entry.getCrc() != crc.getValue())
                    reject("zip_entry_invalid");
                if (HtmlInspectorInjector.isHtml(output)) html.add(output);
            }
        } catch (IOException malformed) {
            throw PrototypeHubException.badRequest(
                    "error.prototype-hub.zip_entry_invalid", "PROTOTYPE_HUB_ZIP_ENTRY_INVALID");
        }
        Path entry =
                html.stream()
                        .filter(
                                p ->
                                        destination.relativize(p).getNameCount()
                                                <= properties.getMaxEntrySearchDepth())
                        .filter(
                                p ->
                                        !destination
                                                .relativize(p)
                                                .toString()
                                                .matches("(?i).*(?:resources|data|plugins)/.*"))
                        .min(
                                Comparator.<Path>comparingInt(
                                                p ->
                                                        p.getFileName()
                                                                        .toString()
                                                                        .equalsIgnoreCase(
                                                                                "index.html")
                                                                ? 0
                                                                : p.getFileName()
                                                                                .toString()
                                                                                .equalsIgnoreCase(
                                                                                        "start.html")
                                                                        ? 1
                                                                        : 2)
                                        .thenComparingInt(
                                                p -> destination.relativize(p).getNameCount())
                                        .thenComparing(Path::toString))
                        .orElseThrow(
                                () ->
                                        PrototypeHubException.badRequest(
                                                "error.prototype-hub.entry_not_found",
                                                "PROTOTYPE_HUB_ENTRY_NOT_FOUND"));
        for (Path file : html) inspector.inject(file);
        return destination.relativize(entry).toString().replace('\\', '/');
    }

    private static void reject(String kind) {
        throw PrototypeHubException.badRequest(
                "error.prototype-hub." + kind, "PROTOTYPE_HUB_" + kind.toUpperCase(Locale.ROOT));
    }

    public static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(directory)) {
            Files.delete(directory);
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(item);
        }
    }
}
