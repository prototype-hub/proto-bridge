package com.gaiys.proto.prototypehub.storage.materials.text;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** PDFBox只在可销毁的短命JVM执行；线程取消或三十秒到期会杀掉子进程并清私有结果。 */
final class PdfTextProcess {
    private PdfTextProcess() {}

    static MaterialTextExtractor.Result extract(Path input) throws IOException {
        // 隔离user.home会重建系统字体索引；预算覆盖冷启动，仍保留子进程硬截止。
        return extract(input, 30_000);
    }

    static MaterialTextExtractor.Result extract(Path input, long timeoutMillis) throws IOException {
        Path directory = Files.createTempDirectory("prototype-material-pdf-");
        try {
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix"))
                Files.setPosixFilePermissions(
                        directory, PosixFilePermissions.fromString("rwx------"));
            Path result = directory.resolve("text");
            String classpath = classpath();
            var command = new ArrayList<String>();
            command.add(
                    Path.of(
                                    System.getProperty("java.home"),
                                    "bin",
                                    System.getProperty("os.name").startsWith("Windows")
                                            ? "java.exe"
                                            : "java")
                            .toString());
            // 堆和元空间限制属于子JVM；超时直接销毁，不把Reactor取消当PDFBox已停止。
            command.addAll(
                    java.util.List.of(
                            "-Xmx128m",
                            "-XX:MaxMetaspaceSize=64m",
                            "-XX:ActiveProcessorCount=1",
                            "-Djava.awt.headless=true",
                            "-Duser.home=" + directory,
                            "-Djava.io.tmpdir=" + directory));
            boolean boot = bootClasspath(classpath);
            if (boot) command.add("-Dloader.main=" + PdfTextWorker.class.getName());
            command.addAll(
                    java.util.List.of(
                            "-cp",
                            classpath,
                            boot
                                    ? "org.springframework.boot.loader.launch.PropertiesLauncher"
                                    : PdfTextWorker.class.getName(),
                            input.toAbsolutePath().toString(),
                            result.toString()));
            ProcessBuilder builder =
                    new ProcessBuilder(command)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD);
            isolate(builder, directory);
            Process process = builder.start();
            try {
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS))
                    return MaterialTextExtractor.result(
                            MaterialTextExtractor.Status.LIMIT_EXCEEDED);
                return switch (process.exitValue()) {
                    case 0 -> {
                        if (Files.size(result) > MaterialTextExtractor.MAX_CHARACTERS * 4L)
                            throw new MaterialTextExtractor.LimitExceeded();
                        String content = Files.readString(result, StandardCharsets.UTF_8);
                        if (content.length() > MaterialTextExtractor.MAX_CHARACTERS)
                            throw new MaterialTextExtractor.LimitExceeded();
                        yield MaterialTextExtractor.result(content);
                    }
                    case 3 -> MaterialTextExtractor.result(MaterialTextExtractor.Status.ENCRYPTED);
                    case 4 ->
                            MaterialTextExtractor.result(
                                    MaterialTextExtractor.Status.LIMIT_EXCEEDED);
                    default -> MaterialTextExtractor.result(MaterialTextExtractor.Status.FAILED);
                };
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("extraction interrupted", e);
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
                for (Path path : files.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    /** 子进程不继承数据库/管理员凭据或loader配置，且不能从应用工作目录拾取loader.properties。 */
    static void isolate(ProcessBuilder builder, Path directory) {
        builder.directory(directory.toFile());
        builder.environment()
                .keySet()
                .removeIf(
                        name ->
                                !java.util.Set.of(
                                                "SYSTEMROOT",
                                                "WINDIR",
                                                "SYSTEMDRIVE",
                                                "LANG",
                                                "LC_ALL",
                                                "TZ")
                                        .contains(name.toUpperCase(java.util.Locale.ROOT)));
    }

    static String classpath() {
        return java.util.Arrays.stream(
                        System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path"))
                                .split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    static boolean bootClasspath(String classpath) throws IOException {
        String launcher = "org/springframework/boot/loader/launch/PropertiesLauncher.class";
        for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path path = Path.of(entry);
            // Docker按Boot分层解包后classpath是目录；仍须由Boot加载BOOT-INF中的模块。
            if (Files.isDirectory(path.resolve("BOOT-INF/classes"))
                    && Files.isRegularFile(path.resolve(launcher))) return true;
            if (!entry.endsWith(".jar") || !Files.isRegularFile(Path.of(entry))) continue;
            try (ZipFile archive = new ZipFile(entry)) {
                if (archive.getEntry("BOOT-INF/classes/") != null
                        && archive.getEntry(launcher) != null) return true;
            }
        }
        return false;
    }
}
