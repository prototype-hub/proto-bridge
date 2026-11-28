package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeDesignTokenPackageEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.validation.DesignTokenParser;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.materials.RevisionMaterialFiles;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.response.DesignTokenDocument;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
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

/** 有界原文件I/O和解析边界；候选归属必须等待实际写入和事务完成后判断。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrototypeDesignTokenPackageFileService {
    private final PrototypeHubStorageProperties properties;
    private final FileWork work;
    private final FileCleanupService cleanup;
    private final TransactionalOperator transactions;
    private final DesignTokenParser parser = new DesignTokenParser();

    public record Candidate(
            String path, String fileName, long size, String sha256, DesignTokenDocument document) {}

    public record Stored<T>(T response, String storagePath) {}

    public Mono<DesignTokenDocument> validate(FilePart file) {
        return Mono.defer(
                () -> {
                    RevisionMaterialFiles.filename(file.filename());
                    return bytes(file).flatMap(bytes -> work.call(() -> parser.parse(bytes)));
                });
    }

    public <T> Mono<T> upload(
            String project,
            String version,
            FilePart file,
            boolean allowInvalid,
            Function<Candidate, Mono<Stored<T>>> operation) {
        return Mono.defer(
                        () -> {
                            String name = RevisionMaterialFiles.filename(file.filename());
                            String path =
                                    "projects/"
                                            + SafePaths.id(project)
                                            + "/design-token-packages/"
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
                                                                            project,
                                                                            path,
                                                                            name,
                                                                            bytes,
                                                                            allowInvalid,
                                                                            owned))
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
                .checkpoint("prototype-hub/token-package/file-upload");
    }

    private Mono<byte[]> bytes(FilePart file) {
        return DataBufferUtils.join(file.content(), DesignTokenParser.MAX_BYTES)
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
                .defaultIfEmpty(new byte[0])
                .onErrorMap(DataBufferLimitException.class, ignored -> tooLargeOrEmpty());
    }

    private Mono<Candidate> write(
            String project,
            String path,
            String name,
            byte[] bytes,
            boolean allowInvalid,
            Attempt attempt) {
        return work.call(
                () -> {
                    if (!attempt.beginWriting()) throw new CancellationException();
                    try {
                        var document = parser.parse(bytes);
                        if (!document.isValid() && !allowInvalid)
                            throw PrototypeHubException.badRequest(
                                    "error.prototype-hub.token_package_semantic_invalid",
                                    "PROTOTYPE_HUB_TOKEN_PACKAGE_SEMANTIC_INVALID");
                        var target =
                                SafePaths.designTokenPackage(properties.getRoot(), project, path);
                        Files.createDirectories(target.getParent().getParent());
                        Files.createDirectory(target.getParent());
                        attempt.ownDirectory();
                        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
                        return new Candidate(path, name, bytes.length, hash(bytes), document);
                    } finally {
                        attempt.completeWriting();
                    }
                });
    }

    public Mono<DesignTokenDocument> document(
            PrototypeDesignTokenPackageEntity row, Map<String, String> inputs) {
        return work.call(
                        () ->
                                inputs == null
                                        ? parser.parse(verifiedBytes(row))
                                        : parser.parse(verifiedBytes(row), inputs))
                .onErrorMap(java.io.IOException.class, ignored -> missing());
    }

    public Mono<ResponseEntity<Resource>> read(PrototypeDesignTokenPackageEntity row) {
        return work.call(
                        () ->
                                ResponseEntity.ok()
                                        // 原包可含业务信封同名字段；作为纯附件避免客户端误解为API响应。
                                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
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
                                        .body((Resource) new ByteArrayResource(verifiedBytes(row))))
                .onErrorMap(java.io.IOException.class, ignored -> missing());
    }

    private byte[] verifiedBytes(PrototypeDesignTokenPackageEntity row) throws java.io.IOException {
        var file =
                SafePaths.designTokenPackage(
                        properties.getRoot(), row.getProjectId(), row.getStoragePath());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || row.getFileSize() < 1
                || row.getFileSize() > DesignTokenParser.MAX_BYTES
                || Files.size(file) != row.getFileSize()) throw missing();
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(DesignTokenParser.MAX_BYTES + 1);
        }
        if (bytes.length != row.getFileSize() || !hash(bytes).equals(row.getSha256()))
            throw missing();
        return bytes;
    }

    public Mono<Void> delete(String project, String path) {
        return work.run(
                () ->
                        SecureZipExtractor.deleteRecursively(
                                SafePaths.designTokenPackage(properties.getRoot(), project, path)
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
                                                                    "Token package cleanup pending: errorType={}",
                                                                    error.getClass()
                                                                            .getSimpleName());
                                                            return cleanup.enqueueTokenPackage(
                                                                            project, version, path)
                                                                    .onErrorResume(
                                                                            databaseError -> {
                                                                                log.error(
                                                                                        "Unable to persist token package cleanup: errorType={}",
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

    private static PrototypeHubException tooLargeOrEmpty() {
        return new PrototypeHubException(
                "error.prototype-hub.token_package_file_limit",
                "PROTOTYPE_HUB_TOKEN_PACKAGE_FILE_LIMIT",
                413);
    }

    public static PrototypeHubException missing() {
        return PrototypeHubException.notFound(
                "error.prototype-hub.token_package_file_missing",
                "PROTOTYPE_HUB_TOKEN_PACKAGE_FILE_MISSING");
    }

    private record Outcome(int status, boolean owned) {}

    /** 沿F13归属协议保留未知提交；取消必须等物理writer finally，不能先清目录再让迟到写入创建文件。 */
    static final class Attempt {
        final Sinks.One<Outcome> completion = Sinks.one();
        private final Sinks.One<Void> writeCompletion = Sinks.one();
        private int writing;
        private boolean writeClosed, directory, enlisted, closed, owned;

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
