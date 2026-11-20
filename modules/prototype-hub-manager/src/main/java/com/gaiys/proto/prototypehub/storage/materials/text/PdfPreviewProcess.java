package com.gaiys.proto.prototypehub.storage.materials.text;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 只在FileWork内按次绘制已验证原件的私有副本；短命子JVM退出后销毁全部输入与PNG，不建立缓存或存储引用。 */
public final class PdfPreviewProcess {
    public static final int MAX_PAGES = 100;
    public static final int MAX_DIMENSION = 4096;
    public static final int MAX_PIXELS = 4_000_000;
    public static final int MAX_PNG_BYTES = 16 * 1024 * 1024;

    private PdfPreviewProcess() {}

    public record Page(byte[] bytes, int page, int pages, int width, int height) {}

    public static Page render(byte[] verified, int page) throws IOException {
        return render(verified, page, 30_000);
    }

    static Page render(byte[] verified, int page, long timeoutMillis) throws IOException {
        if (verified.length > MaterialTextExtractor.MAX_INPUT_BYTES)
            throw error("LIMIT_EXCEEDED", 413);
        Path directory = Files.createTempDirectory("prototype-material-preview-");
        try {
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix"))
                Files.setPosixFilePermissions(
                        directory, PosixFilePermissions.fromString("rwx------"));
            Path input = directory.resolve("input.pdf");
            Path output = directory.resolve("page.png");
            Path metadata = directory.resolve("metadata");
            Files.write(input, verified);
            String classpath = PdfTextProcess.classpath();
            boolean boot = PdfTextProcess.bootClasspath(classpath);
            var command = new ArrayList<String>();
            command.add(
                    Path.of(
                                    System.getProperty("java.home"),
                                    "bin",
                                    System.getProperty("os.name").startsWith("Windows")
                                            ? "java.exe"
                                            : "java")
                            .toString());
            command.addAll(
                    List.of(
                            "-Xmx128m",
                            "-XX:MaxMetaspaceSize=64m",
                            "-XX:ActiveProcessorCount=1",
                            "-Djava.awt.headless=true",
                            "-Duser.home=" + directory,
                            "-Djava.io.tmpdir=" + directory));
            if (boot) command.add("-Dloader.main=" + PdfPreviewWorker.class.getName());
            command.addAll(
                    List.of(
                            "-cp",
                            classpath,
                            boot
                                    ? "org.springframework.boot.loader.launch.PropertiesLauncher"
                                    : PdfPreviewWorker.class.getName(),
                            input.toString(),
                            output.toString(),
                            metadata.toString(),
                            Integer.toString(page)));
            ProcessBuilder builder =
                    new ProcessBuilder(command)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD);
            PdfTextProcess.isolate(builder, directory);
            Process process = builder.start();
            try {
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS))
                    throw error("LIMIT_EXCEEDED", 413);
                switch (process.exitValue()) {
                    case 0 -> {
                        return result(output, metadata, page);
                    }
                    case 3 -> throw error("ENCRYPTED", 422);
                    case 4 -> throw error("LIMIT_EXCEEDED", 413);
                    case 6 -> throw error("PAGE_INVALID", 400);
                    default -> throw error("INVALID", 422);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("preview interrupted", e);
            } finally {
                process.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
    }

    private static Page result(Path output, Path metadata, int page) throws IOException {
        if (!Files.isRegularFile(output)
                || !Files.isRegularFile(metadata)
                || Files.size(output) > MAX_PNG_BYTES
                || Files.size(metadata) > 128) throw error("INVALID", 422);
        try {
            String[] values = Files.readString(metadata).split(",");
            if (values.length != 3) throw error("INVALID", 422);
            int pages = Integer.parseInt(values[0]);
            int width = Integer.parseInt(values[1]);
            int height = Integer.parseInt(values[2]);
            byte[] bytes;
            try (var input = Files.newInputStream(output)) {
                bytes = input.readNBytes(MAX_PNG_BYTES + 1);
            }
            if (pages < 1
                    || pages > MAX_PAGES
                    || page < 0
                    || page >= pages
                    || width < 1
                    || height < 1
                    || width > MAX_DIMENSION
                    || height > MAX_DIMENSION
                    || (long) width * height > MAX_PIXELS
                    || bytes.length < 24
                    || bytes.length > MAX_PNG_BYTES
                    || !Arrays.equals(
                            Arrays.copyOf(bytes, 8),
                            new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})
                    || ByteBuffer.wrap(bytes, 16, 8).getInt() != width
                    || ByteBuffer.wrap(bytes, 20, 4).getInt() != height)
                throw error("INVALID", 422);
            return new Page(bytes, page, pages, width, height);
        } catch (NumberFormatException e) {
            throw error("INVALID", 422);
        }
    }

    public static PrototypeHubException error(String reason, int status) {
        return new PrototypeHubException(
                "error.prototype-hub.material_preview_" + reason.toLowerCase(java.util.Locale.ROOT),
                "PROTOTYPE_HUB_MATERIAL_PREVIEW_" + reason,
                status);
    }
}
