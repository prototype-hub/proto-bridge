package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewMessageEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.response.ReviewMessageResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
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

/** 单张评审图片的有界校验和私有文件生命周期；数据库提交结果决定候选文件归属。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrototypeReviewImageService {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private final PrototypeHubStorageProperties properties;
    private final FileWork work;
    private final PrototypeStorageService storage;
    private final FileCleanupService cleanup;
    private final TransactionalOperator transactions;

    /** 调用方先完全完成授权读取；文件解码先于短事务，事务内再次校验范围并持久化消息。 */
    public Mono<ReviewMessageResponse> upload(
            String project,
            String version,
            FilePart file,
            Function<Candidate, Mono<ReviewMessageResponse>> operation) {
        return Mono.defer(
                        () -> {
                            String id = PrototypeReviewKeys.random();
                            String path =
                                    "projects/"
                                            + SafePaths.id(project)
                                            + "/review-images/"
                                            + id
                                            + "/image";
                            Attempt attempt = new Attempt();
                            return Mono.usingWhen(
                                    Mono.just(attempt),
                                    owned ->
                                            bytes(file)
                                                    .flatMap(
                                                            bytes ->
                                                                    work.call(
                                                                            () -> {
                                                                                if (!owned
                                                                                        .beginWriting())
                                                                                    throw new CancellationException();
                                                                                try {
                                                                                    var metadata =
                                                                                            inspect(
                                                                                                    bytes);
                                                                                    Path target =
                                                                                            SafePaths
                                                                                                    .reviewImage(
                                                                                                            properties
                                                                                                                    .getRoot(),
                                                                                                            project,
                                                                                                            path);
                                                                                    Files
                                                                                            .createDirectories(
                                                                                                    target.getParent()
                                                                                                            .getParent());
                                                                                    Files
                                                                                            .createDirectory(
                                                                                                    target
                                                                                                            .getParent());
                                                                                    owned
                                                                                            .ownDirectory();
                                                                                    Files.write(
                                                                                            target,
                                                                                            bytes,
                                                                                            StandardOpenOption
                                                                                                    .CREATE_NEW);
                                                                                    return new Candidate(
                                                                                            id,
                                                                                            path,
                                                                                            metadata
                                                                                                    .contentType(),
                                                                                            bytes.length,
                                                                                            hash(
                                                                                                    bytes),
                                                                                            metadata
                                                                                                    .width(),
                                                                                            metadata
                                                                                                    .height());
                                                                                } finally {
                                                                                    owned
                                                                                            .completeWriting();
                                                                                }
                                                                            }))
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
                                                                                                                                            candidate
                                                                                                                                                    .id()
                                                                                                                                                    .equals(
                                                                                                                                                            result
                                                                                                                                                                    .id())))
                                                                                                    : Mono
                                                                                                            .empty())
                                                                            .doOnCancel(
                                                                                    owned
                                                                                            ::closeBeforeEnlistment)
                                                                            .as(
                                                                                    transactions
                                                                                            ::transactional)),
                                    ignored -> release(project, version, path, ignored),
                                    (ignored, error) -> release(project, version, path, ignored),
                                    ignored -> release(project, version, path, ignored));
                        })
                .checkpoint("prototype-hub/review/image-upload");
    }

    private Mono<byte[]> bytes(FilePart file) {
        // 图片源文件最多5MiB；有界聚合避免解码前引入无界内存或不受控临时文件。
        return DataBufferUtils.join(file.content(), MAX_BYTES)
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
                .switchIfEmpty(Mono.error(PrototypeReviewImageService::invalid))
                .onErrorMap(DataBufferLimitException.class, ignored -> tooLarge());
    }

    /** 只接受实际PNG/JPEG；先读头和尺寸，再在有界文件线程完整解码，不信任名称或浏览器MIME。 */
    static ReviewMessageResponse.Image inspect(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw tooLarge();
        boolean png =
                bytes.length >= 20
                        && bytes[0] == (byte) 0x89
                        && bytes[1] == 'P'
                        && bytes[2] == 'N'
                        && bytes[3] == 'G'
                        && bytes[4] == 13
                        && bytes[5] == 10
                        && bytes[6] == 26
                        && bytes[7] == 10;
        boolean jpeg =
                bytes.length >= 4
                        && bytes[0] == (byte) 0xff
                        && bytes[1] == (byte) 0xd8
                        && bytes[bytes.length - 2] == (byte) 0xff
                        && bytes[bytes.length - 1] == (byte) 0xd9;
        if (!png && !jpeg) throw invalid();
        if (png
                && (bytes.length < 12
                        || bytes[bytes.length - 8] != 'I'
                        || bytes[bytes.length - 7] != 'E'
                        || bytes[bytes.length - 6] != 'N'
                        || bytes[bytes.length - 5] != 'D')) throw invalid();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!(png && "png".equalsIgnoreCase(format))
                        && !(jpeg && "jpeg".equalsIgnoreCase(format))) throw invalid();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1
                        || height < 1
                        || width > 16384
                        || height > 16384
                        || (long) width * height > 16_000_000) throw invalid();
                if (reader.read(0) == null) throw invalid();
                return new ReviewMessageResponse.Image(
                        png ? "image/png" : "image/jpeg", bytes.length, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException error) {
            throw invalid();
        }
    }

    /** 数据库全部授权和消息读取结束后再读文件；先校验整文件摘要，再交付相同字节避免二次打开竞态。 */
    public Mono<ResponseEntity<Resource>> read(PrototypeReviewMessageEntity row) {
        return work.call(
                        () -> {
                            var metadata = metadata(row);
                            if (metadata == null) throw unavailable();
                            try {
                                Path file =
                                        SafePaths.reviewImage(
                                                properties.getRoot(),
                                                row.getProjectId(),
                                                row.getImagePath());
                                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                        || Files.size(file) != row.getImageSize())
                                    throw unavailable();
                                byte[] bytes;
                                try (var input = Files.newInputStream(file)) {
                                    bytes = input.readNBytes(MAX_BYTES + 1);
                                }
                                if (bytes.length != row.getImageSize()
                                        || !hash(bytes).equals(row.getImageSha256()))
                                    throw unavailable();
                                if (!metadata.equals(inspect(bytes))) throw unavailable();
                                return ResponseEntity.ok()
                                        .contentType(
                                                MediaType.parseMediaType(metadata.contentType()))
                                        .contentLength(metadata.size())
                                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                                        .header("X-Content-Type-Options", "nosniff")
                                        .header(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                ContentDisposition.inline()
                                                        .filename(
                                                                "image/png"
                                                                                .equals(
                                                                                        metadata
                                                                                                .contentType())
                                                                        ? "image.png"
                                                                        : "image.jpg")
                                                        .build()
                                                        .toString())
                                        .body((Resource) new ByteArrayResource(bytes));
                            } catch (IOException | PrototypeHubException error) {
                                throw unavailable();
                            }
                        })
                .checkpoint("prototype-hub/review/image-read");
    }

    /** 旧消息或不完整元数据只呈现无图；下载边界仍显式拒绝。 */
    public static ReviewMessageResponse.Image metadata(PrototypeReviewMessageEntity row) {
        if (row.getImagePath() == null
                || row.getImageSha256() == null
                || !row.getImageSha256().matches("[a-f0-9]{64}")
                || !("image/png".equals(row.getImageContentType())
                        || "image/jpeg".equals(row.getImageContentType()))
                || row.getImageSize() == null
                || row.getImageSize() < 1
                || row.getImageSize() > MAX_BYTES
                || row.getImageWidth() == null
                || row.getImageHeight() == null
                || row.getImageWidth() < 1
                || row.getImageHeight() < 1
                || row.getImageWidth() > 16384
                || row.getImageHeight() > 16384
                || (long) row.getImageWidth() * row.getImageHeight() > 16_000_000) return null;
        return new ReviewMessageResponse.Image(
                row.getImageContentType(),
                row.getImageSize(),
                row.getImageWidth(),
                row.getImageHeight());
    }

    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(PrototypeCreationReceiptService.sha256().digest(bytes));
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
                                        return storage.deleteReviewImage(project, path)
                                                .onErrorResume(
                                                        error -> {
                                                            log.warn(
                                                                    "Review image cleanup pending: result=retry, errorType={}",
                                                                    error.getClass()
                                                                            .getSimpleName());
                                                            return cleanup.enqueueReviewImage(
                                                                            project, version, path)
                                                                    .onErrorResume(
                                                                            databaseError -> {
                                                                                log.error(
                                                                                        "Unable to persist review image cleanup: errorType={}",
                                                                                        databaseError
                                                                                                .getClass()
                                                                                                .getSimpleName());
                                                                                return Mono.empty();
                                                                            });
                                                        });
                                    });
                });
    }

    static PrototypeHubException invalid() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.review_image_invalid", "PROTOTYPE_HUB_REVIEW_IMAGE_INVALID");
    }

    static PrototypeHubException tooLarge() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.review_image_too_large",
                "PROTOTYPE_HUB_REVIEW_IMAGE_TOO_LARGE");
    }

    public static PrototypeHubException unavailable() {
        return PrototypeHubException.notFound(
                "error.prototype-hub.review_image_unavailable",
                "PROTOTYPE_HUB_REVIEW_IMAGE_UNAVAILABLE");
    }

    /** 已验证的服务端候选，只在一次请求中传递，客户端不能提供其路径或摘要。 */
    public record Candidate(
            String id,
            String path,
            String contentType,
            long size,
            String sha256,
            int width,
            int height) {
        void apply(PrototypeReviewMessageEntity row) {
            row.setImagePath(path);
            row.setImageContentType(contentType);
            row.setImageSize(size);
            row.setImageSha256(sha256);
            row.setImageWidth(width);
            row.setImageHeight(height);
        }
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
