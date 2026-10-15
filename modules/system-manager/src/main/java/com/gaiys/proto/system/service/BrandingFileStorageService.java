package com.gaiys.proto.system.service;

import com.gaiys.proto.system.service.support.ManagementSupport;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.file.FileUploadProperties;
import org.hswebframework.web.file.service.FileStorageService;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** hsweb storage SPI adapted to the branding upload contract. */
@Service
@RequiredArgsConstructor
public class BrandingFileStorageService implements FileStorageService {
    private final FileUploadProperties properties;
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    @Override
    public Mono<String> saveFile(FilePart file) {
        return DataBufferUtils.join(file.content(), MAX_BYTES)
                .onErrorMap(
                        org.springframework.core.io.buffer.DataBufferLimitException.class,
                        ignored -> ManagementSupport.invalid())
                .map(
                        buffer -> {
                            try {
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                return bytes;
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        })
                .switchIfEmpty(Mono.error(ManagementSupport.invalid()))
                .flatMap(bytes -> save(bytes, file.filename()));
    }

    @Override
    public Mono<String> saveFile(InputStream stream, String type) {
        return Mono.fromCallable(
                        () -> {
                            try (stream) {
                                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                                if (bytes.length > MAX_BYTES) throw ManagementSupport.invalid();
                                return bytes;
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(bytes -> save(bytes, "upload." + type.replace(".", "")));
    }

    private Mono<String> save(byte[] bytes, String filename) {
        String suffix = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        boolean valid =
                switch (suffix) {
                    case "png" ->
                            bytes.length > 8
                                    && bytes[0] == (byte) 137
                                    && bytes[1] == 80
                                    && bytes[2] == 78
                                    && bytes[3] == 71
                                    && bytes[4] == 13
                                    && bytes[5] == 10
                                    && bytes[6] == 26
                                    && bytes[7] == 10;
                    case "jpg", "jpeg" ->
                            bytes.length > 3
                                    && bytes[0] == (byte) 255
                                    && bytes[1] == (byte) 216
                                    && bytes[2] == (byte) 255;
                    case "gif" ->
                            bytes.length > 6
                                    && new String(
                                                    bytes,
                                                    0,
                                                    6,
                                                    java.nio.charset.StandardCharsets.US_ASCII)
                                            .matches("GIF8[79]a");
                    case "ico" ->
                            bytes.length > 6
                                    && bytes[0] == 0
                                    && bytes[1] == 0
                                    && bytes[2] == 1
                                    && bytes[3] == 0;
                    case "webp" ->
                            bytes.length > 12
                                    && new String(
                                                    bytes,
                                                    0,
                                                    4,
                                                    java.nio.charset.StandardCharsets.US_ASCII)
                                            .equals("RIFF")
                                    && new String(
                                                    bytes,
                                                    8,
                                                    4,
                                                    java.nio.charset.StandardCharsets.US_ASCII)
                                            .equals("WEBP");
                    default -> false;
                };
        if (!valid) return Mono.error(ManagementSupport.invalid());
        return Mono.fromCallable(
                        () -> {
                            Path root =
                                    Path.of(properties.getStaticFilePath())
                                            .toAbsolutePath()
                                            .normalize();
                            String day =
                                    java.time.LocalDate.now()
                                            .format(
                                                    java.time.format.DateTimeFormatter
                                                            .BASIC_ISO_DATE);
                            Path directory = root.resolve(day);
                            Files.createDirectories(directory);
                            String name = UUID.randomUUID() + "." + suffix;
                            Files.write(
                                    directory.resolve(name), bytes, StandardOpenOption.CREATE_NEW);
                            return "/api/file/static/" + day + "/" + name;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<org.springframework.core.io.Resource> resource(String day, String name) {
        if (!day.matches("[0-9]{8}") || !name.matches("[a-f0-9-]{36}\\.(png|jpe?g|gif|ico|webp)"))
            return Mono.error(ManagementSupport.missing());
        return Mono.fromCallable(
                        () -> {
                            Path path =
                                    Path.of(properties.getStaticFilePath())
                                            .toAbsolutePath()
                                            .normalize()
                                            .resolve(day)
                                            .resolve(name);
                            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path))
                                throw ManagementSupport.missing();
                            return (org.springframework.core.io.Resource)
                                    new org.springframework.core.io.FileSystemResource(path);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
