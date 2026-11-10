package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.materials.RevisionMaterialFiles;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** 私有资料的原字节、格式校验及候选归属；提交未知时保留文件，由持久引用决定清理。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrototypeRevisionMaterialFileService {
    private final PrototypeHubStorageProperties properties;
    private final FileWork work;
    private final FileCleanupService cleanup;
    private final TransactionalOperator transactions;

    public record Candidate(
            String path, String fileName, String type, String mediaType, long size, String sha256) {
        void apply(PrototypeRevisionMaterialEntity row) {
            row.setStoragePath(path);
            row.setFileName(fileName);
            row.setMaterialType(type);
            row.setMediaType(mediaType);
            row.setFileSize(size);
            row.setSha256(sha256);
        }
    }

    public record Stored<T>(T response, String storagePath) {}

    public <T> Mono<T> upload(
            String project,
            String version,
            FilePart file,
            Function<Candidate, Mono<Stored<T>>> operation) {
        return Mono.defer(
                        () -> {
                            String name = RevisionMaterialFiles.filename(file.filename());
                            String path =
                                    "projects/"
                                            + SafePaths.id(project)
                                            + "/materials/"
                                            + PrototypeShareService.random()
                                            + "/content";
                            Attempt attempt = new Attempt();
                            return Mono.usingWhen(
                                    Mono.just(attempt),
                                    owned ->
                                            bytes(file)
                                                    .flatMap(
                                                            bytes ->
                                                                    write(
                                                                            project, path, name,
                                                                            bytes, owned))
                                                    .flatMap(
                                                            candidate ->
                                                                    TransactionSynchronizationManager
                                                                            .forCurrentTransaction()
                                                                            .flatMap(
                                                                                    manager ->
                                                                                            owned
                                                                                                            .enlist(
                                                                                                                    manager)
                                                                                                    ? operation
                                                                                                            .apply(
                                                                                                                    candidate)
                                                                                                            .doOnNext(
                                                                                                                    result ->
                                                                                                                            owned
                                                                                                                                    .candidateOwned(
                                                                                                                                            path
                                                                                                                                                    .equals(
                                                                                                                                                            result
                                                                                                                                                                    .storagePath())))
                                                                                                            .map(
                                                                                                                    Stored
                                                                                                                            ::response)
                                                                                                    : Mono
                                                                                                            .empty())
                                                                            .doOnCancel(
                                                                                    owned
                                                                                            ::closeBeforeEnlistment)
                                                                            .as(
                                                                                    transactions
                                                                                            ::transactional)),
                                    owned -> release(project, version, path, owned),
                                    (owned, error) -> release(project, version, path, owned),
                                    owned -> release(project, version, path, owned));
                        })
                .checkpoint("prototype-hub/material/file-upload");
    }

    private Mono<byte[]> bytes(FilePart file) {
        // 文档最多20MiB、图片还受5MiB限制；聚合有界，后续同步文件与解码只在FileWork运行。
        return DataBufferUtils.join(file.content(), RevisionMaterialFiles.MAX_DOCUMENT_BYTES)
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
                .switchIfEmpty(Mono.error(RevisionMaterialFiles::invalid))
                .onErrorMap(
                        DataBufferLimitException.class,
                        ignored -> RevisionMaterialFiles.tooLarge());
    }

    private Mono<Candidate> write(
            String project, String path, String name, byte[] bytes, Attempt attempt) {
        return work.call(
                () -> {
                    if (!attempt.beginWriting()) throw new CancellationException();
                    try {
                        Path target = SafePaths.material(properties.getRoot(), project, path);
                        Files.createDirectories(target.getParent().getParent());
                        Files.createDirectory(target.getParent());
                        attempt.ownDirectory();
                        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
                        var metadata = RevisionMaterialFiles.inspect(bytes, name, target);
                        if (metadata.type().equals("image")) {
                            if (bytes.length > PrototypeReviewImageService.MAX_BYTES)
                                throw RevisionMaterialFiles.tooLarge();
                            try {
                                if (!metadata.mediaType()
                                        .equals(
                                                PrototypeReviewImageService.inspect(bytes)
                                                        .contentType()))
                                    throw RevisionMaterialFiles.invalid();
                            } catch (Exception error) {
                                throw RevisionMaterialFiles.invalid();
                            }
                        }
                        return new Candidate(
                                path,
                                name,
                                metadata.type(),
                                metadata.mediaType(),
                                bytes.length,
                                hash(bytes));
                    } catch (java.io.IOException
                            | IllegalArgumentException
                            | ArithmeticException error) {
                        throw RevisionMaterialFiles.invalid();
                    } finally {
                        attempt.completeWriting();
                    }
                });
    }

    /** 历史原型只引用现有原ZIP；摘要在持锁前计算，不制造或复制来源文件。 */
    public Mono<Candidate> prototype(PrototypeRevisionEntity revision) {
        return work.call(
                () -> {
                    Path directory =
                            SafePaths.storedVersion(
                                    properties.getRoot(),
                                    revision.getProjectId(),
                                    revision.getStoragePath());
                    Path file = SafePaths.within(directory, "original.zip");
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        throw RevisionMaterialFiles.missing();
                    long size = Files.size(file);
                    if (size < 1 || size > properties.getMaxUploadSizeBytes())
                        throw RevisionMaterialFiles.missing();
                    return new Candidate(
                            properties
                                    .getRoot()
                                    .toAbsolutePath()
                                    .normalize()
                                    .relativize(file)
                                    .toString(),
                            "original.zip",
                            "prototype",
                            "application/zip",
                            size,
                            hash(file));
                });
    }

    public Mono<ResponseEntity<Resource>> read(PrototypeRevisionMaterialEntity row) {
        return work.call(
                        () -> {
                            Path file;
                            boolean prototype = "prototype".equals(row.getMaterialType());
                            if (prototype) {
                                if (!row.getStoragePath().endsWith("/original.zip"))
                                    throw RevisionMaterialFiles.missing();
                                String site =
                                        row.getStoragePath()
                                                        .substring(
                                                                0,
                                                                row.getStoragePath().length() - 12)
                                                + "site";
                                file =
                                        SafePaths.within(
                                                SafePaths.storedVersion(
                                                        properties.getRoot(),
                                                        row.getProjectId(),
                                                        site),
                                                "original.zip");
                            } else
                                file =
                                        SafePaths.material(
                                                properties.getRoot(),
                                                row.getProjectId(),
                                                row.getStoragePath());
                            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                    || Files.size(file) != row.getFileSize()
                                    || row.getFileSize() < 1
                                    || row.getFileSize()
                                            > (prototype
                                                    ? properties.getMaxUploadSizeBytes()
                                                    : RevisionMaterialFiles.MAX_DOCUMENT_BYTES))
                                throw RevisionMaterialFiles.missing();
                            Resource resource;
                            if (prototype) {
                                if (!hash(file).equals(row.getSha256()))
                                    throw RevisionMaterialFiles.missing();
                                resource = new FileSystemResource(file);
                            } else {
                                byte[] bytes;
                                try (var input = Files.newInputStream(file)) {
                                    bytes =
                                            input.readNBytes(
                                                    RevisionMaterialFiles.MAX_DOCUMENT_BYTES + 1);
                                }
                                if (bytes.length != row.getFileSize()
                                        || !hash(bytes).equals(row.getSha256()))
                                    throw RevisionMaterialFiles.missing();
                                resource = new ByteArrayResource(bytes);
                            }
                            return ResponseEntity.ok()
                                    .contentType(MediaType.parseMediaType(row.getMediaType()))
                                    .contentLength(row.getFileSize())
                                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                                    .header("X-Content-Type-Options", "nosniff")
                                    .header(
                                            "Content-Security-Policy",
                                            "sandbox; default-src 'none'")
                                    .header(
                                            HttpHeaders.CONTENT_DISPOSITION,
                                            ContentDisposition.attachment()
                                                    .filename(
                                                            RevisionMaterialFiles.filename(
                                                                    row.getFileName()),
                                                            StandardCharsets.UTF_8)
                                                    .build()
                                                    .toString())
                                    .body(resource);
                        })
                .checkpoint("prototype-hub/material/file-read");
    }

    public Mono<Void> delete(String project, String path) {
        return work.run(
                () ->
                        SecureZipExtractor.deleteRecursively(
                                SafePaths.material(properties.getRoot(), project, path)
                                        .getParent()));
    }

    Mono<Void> release(String project, String version, String path, Attempt attempt) {
        return Mono.defer(
                () -> {
                    attempt.closeBeforeEnlistment();
                    return attempt.closeWriting()
                            .then(attempt.completion.asMono())
                            .flatMap(
                                    outcome -> {
                                        if (!attempt.ownsDirectory()
                                                || outcome.status()
                                                        == TransactionSynchronization.STATUS_UNKNOWN
                                                || outcome.status()
                                                                == TransactionSynchronization
                                                                        .STATUS_COMMITTED
                                                        && outcome.owned()) return Mono.empty();
                                        return delete(project, path)
                                                .onErrorResume(
                                                        error -> {
                                                            log.warn(
                                                                    "Material cleanup pending: errorType={}",
                                                                    error.getClass()
                                                                            .getSimpleName());
                                                            return cleanup.enqueueMaterial(
                                                                            project, version, path)
                                                                    .onErrorResume(
                                                                            databaseError -> {
                                                                                log.error(
                                                                                        "Unable to persist material cleanup: errorType={}",
                                                                                        databaseError
                                                                                                .getClass()
                                                                                                .getSimpleName());
                                                                                return Mono.empty();
                                                                            });
                                                        });
                                    });
                });
    }

    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(PrototypeCreationReceiptService.sha256().digest(bytes));
    }

    private static String hash(Path file) throws java.io.IOException {
        var digest = PrototypeCreationReceiptService.sha256();
        try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record Outcome(int status, boolean owned) {}

    /** 单次上传状态；先等待物理finally和数据库afterCompletion，再判断是否删除文件。 */
    static final class Attempt {
        final Sinks.One<Outcome> completion = Sinks.one();
        private final Sinks.One<Void> writeCompletion = Sinks.one();
        private int writing;
        private boolean writeClosed;
        private boolean directory;
        private boolean enlisted;
        private boolean closed;
        private boolean owned;

        synchronized boolean beginWriting() {
            if (writeClosed) return false;
            writing++;
            return true;
        }

        synchronized void ownDirectory() {
            directory = true;
        }

        synchronized boolean ownsDirectory() {
            return directory;
        }

        void completeWriting() {
            boolean done;
            synchronized (this) {
                writing--;
                done = writeClosed && writing == 0;
            }
            if (done) writeCompletion.tryEmitEmpty();
        }

        Mono<Void> closeWriting() {
            boolean done;
            synchronized (this) {
                writeClosed = true;
                done = writing == 0;
            }
            if (done) writeCompletion.tryEmitEmpty();
            return writeCompletion.asMono();
        }

        synchronized boolean enlist(TransactionSynchronizationManager manager) {
            if (closed) return false;
            manager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public Mono<Void> afterCompletion(int status) {
                            boolean candidate;
                            synchronized (Attempt.this) {
                                candidate = owned;
                            }
                            completion.tryEmitValue(new Outcome(status, candidate));
                            return Mono.empty();
                        }
                    });
            enlisted = true;
            return true;
        }

        synchronized void candidateOwned(boolean value) {
            owned = value;
        }

        void closeBeforeEnlistment() {
            synchronized (this) {
                if (enlisted) return;
                closed = true;
            }
            completion.tryEmitValue(new Outcome(-1, false));
        }
    }
}
