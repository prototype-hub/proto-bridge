package com.gaiys.proto.prototypehub.storage.materials;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;

/** 私有原始文档只做有界格式识别，不解析XML、不渲染Office、不提取归档。 */
public final class RevisionMaterialFiles {
    public static final int MAX_DOCUMENT_BYTES = 20 * 1024 * 1024;

    private RevisionMaterialFiles() {}

    public record Metadata(String type, String mediaType) {}

    public static String filename(String name) {
        if (name == null
                || name.isBlank()
                || name.length() > 255
                || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || name.chars().anyMatch(Character::isISOControl)) throw invalid();
        return name;
    }

    public static Metadata inspect(byte[] bytes, String name, Path file) throws IOException {
        String normalized = filename(name).toLowerCase(Locale.ROOT);
        String extension = normalized.substring(normalized.lastIndexOf('.') + 1);
        if (bytes.length == 0) throw invalid();
        if (bytes.length > MAX_DOCUMENT_BYTES) throw tooLarge();
        return switch (extension) {
            case "png" -> new Metadata("image", "image/png");
            case "jpg", "jpeg" -> new Metadata("image", "image/jpeg");
            case "pdf" -> {
                if (bytes.length < 5
                        || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
                    throw invalid();
                yield new Metadata("document", "application/pdf");
            }
            case "txt", "md" -> {
                text(bytes);
                yield new Metadata(
                        "document", extension.equals("md") ? "text/markdown" : "text/plain");
            }
            case "docx", "xlsx", "pptx" -> {
                office(file, extension);
                yield new Metadata(
                        "document",
                        "application/vnd.openxmlformats-officedocument."
                                + switch (extension) {
                                    case "docx" -> "wordprocessingml.document";
                                    case "xlsx" -> "spreadsheetml.sheet";
                                    default -> "presentationml.presentation";
                                });
            }
            default ->
                    throw new PrototypeHubException(
                            "error.prototype-hub.material_type_unsupported",
                            "PROTOTYPE_HUB_MATERIAL_TYPE_UNSUPPORTED",
                            400);
        };
    }

    private static void text(byte[] bytes) throws IOException {
        String value =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
        String header = value.stripLeading().toLowerCase(Locale.ROOT);
        if (value.indexOf('\0') >= 0
                || header.startsWith("<!doctype html")
                || header.startsWith("<html")
                || header.startsWith("<svg")) throw invalid();
    }

    private static void office(Path file, String extension) throws IOException {
        String main =
                switch (extension) {
                    case "docx" -> "word/document.xml";
                    case "xlsx" -> "xl/workbook.xml";
                    default -> "ppt/presentation.xml";
                };
        Set<String> names = new HashSet<>();
        long expanded = 0;
        try (var archive = new ZipFile(file.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (names.size() >= 4096
                        || !names.add(name)
                        || name.startsWith("/")
                        || name.indexOf('\\') >= 0
                        || name.chars().anyMatch(Character::isISOControl)
                        || Path.of(name).isAbsolute()
                        || java.util.Arrays.asList(name.split("/", -1)).contains("..")
                        || lower.contains("vbaproject")
                        || lower.contains("vbasignature")
                        || lower.contains("/macrosheets/")
                        || lower.contains("/intlmacrosheets/")
                        || entry.getSize() < 0) throw invalid();
                expanded = Math.addExact(expanded, entry.getSize());
                if (expanded > 200L * 1024 * 1024) throw invalid();
            }
            if (!names.contains("[Content_Types].xml") || !names.contains(main)) throw invalid();
            // 内容类型只做有界UTF-8标记检查；实体和其他编码不能绕过宏类型拒绝。
            byte[] types;
            try (var input = archive.getInputStream(archive.getEntry("[Content_Types].xml"))) {
                types = input.readNBytes(256 * 1024 + 1);
            }
            if (types.length > 256 * 1024) throw invalid();
            String contentTypes =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(types))
                            .toString()
                            .toLowerCase(Locale.ROOT);
            if (contentTypes.indexOf('\0') >= 0
                    || contentTypes.indexOf('&') >= 0
                    || contentTypes.contains("<!doctype")
                    || contentTypes.contains("<!entity")
                    || contentTypes.contains("macroenabled")
                    || contentTypes.contains("macrosheet")
                    || contentTypes.contains("vbaproject")) throw invalid();
        }
    }

    public static PrototypeHubException invalid() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.material_invalid", "PROTOTYPE_HUB_MATERIAL_INVALID");
    }

    public static PrototypeHubException tooLarge() {
        return new PrototypeHubException(
                "error.prototype-hub.material_too_large", "PROTOTYPE_HUB_MATERIAL_TOO_LARGE", 413);
    }

    public static PrototypeHubException missing() {
        return PrototypeHubException.notFound(
                "error.prototype-hub.material_file_missing", "PROTOTYPE_HUB_MATERIAL_FILE_MISSING");
    }
}
