package com.gaiys.proto.prototypehub.storage.materials.text;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/** 由FileWork按次调用的无状态正文提取器；只读已鉴权原文件，不执行宏、脚本或远端关系。 解析失败返回派生状态，不使原始资料上传失败；不创建常驻任务、缓存或另一套追踪设施。 */
public final class MaterialTextExtractor {
    public static final String VERSION = "material-text-v1";
    public static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;
    public static final int MAX_CHARACTERS = 200_000;
    public static final int MAX_PDF_PAGES = 100;

    public enum Status {
        READY,
        EMPTY,
        UNSUPPORTED,
        ENCRYPTED,
        LIMIT_EXCEEDED,
        FAILED
    }

    public record Result(
            Status status, String content, int characterCount, String extractorVersion) {}

    /** 调用方负责固定资料范围、hash和权限；此层只处理有界文件及纯文本结果。 */
    public Result extract(Path file, String filename, String mediaType) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return result(Status.FAILED);
            if (Files.size(file) > MAX_INPUT_BYTES) return result(Status.LIMIT_EXCEEDED);
            String extension =
                    filename == null
                            ? ""
                            : filename.substring(filename.lastIndexOf('.') + 1)
                                    .toLowerCase(Locale.ROOT);
            String expected =
                    switch (extension) {
                        case "txt" -> "text/plain";
                        case "md" -> "text/markdown";
                        case "pdf" -> "application/pdf";
                        case "docx" ->
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                        case "xlsx" ->
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                        case "pptx" ->
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                        default -> "";
                    };
            if (expected.isEmpty()
                    || mediaType == null
                    || !expected.equalsIgnoreCase(mediaType.split(";", 2)[0].trim()))
                return result(Status.UNSUPPORTED);
            if (extension.equals("pdf")) return PdfTextProcess.extract(file);
            Text text = new Text();
            if (extension.equals("txt") || extension.equals("md")) {
                var decoder =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT);
                try (Reader reader =
                        new java.io.InputStreamReader(Files.newInputStream(file), decoder)) {
                    char[] buffer = new char[4096];
                    for (int size; (size = reader.read(buffer)) != -1; )
                        text.append(new String(buffer, 0, size));
                }
            } else {
                new OfficeText(file).extract(extension, text);
            }
            return result(text.value());
        } catch (LimitExceeded e) {
            return result(Status.LIMIT_EXCEEDED);
        } catch (IOException | RuntimeException | javax.xml.stream.XMLStreamException e) {
            return result(Status.FAILED);
        }
    }

    static Result result(String content) {
        return content.isBlank()
                ? result(Status.EMPTY)
                : new Result(
                        Status.READY,
                        content,
                        content.codePointCount(0, content.length()),
                        VERSION);
    }

    static Result result(Status status) {
        return new Result(status, "", 0, VERSION);
    }

    static final class LimitExceeded extends IOException {}

    /** UTF-16内存预算更保守；超限整份失败，绝不以截断文字冒充完整正文。 */
    static final class Text {
        private final StringBuilder value = new StringBuilder();

        void append(String text) throws IOException {
            if (Thread.currentThread().isInterrupted())
                throw new IOException("extraction interrupted");
            if (text.length() > MAX_CHARACTERS - value.length()) throw new LimitExceeded();
            if (text.indexOf('\0') >= 0) throw new IOException("invalid text");
            value.append(text);
        }

        String value() {
            return value.toString();
        }
    }
}
