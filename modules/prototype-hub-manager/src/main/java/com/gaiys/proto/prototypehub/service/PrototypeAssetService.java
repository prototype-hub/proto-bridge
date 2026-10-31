package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.storage.assets.PrototypeAssetFiles;
import com.gaiys.proto.prototypehub.storage.assets.PrototypeAssetFiles.Format;
import com.gaiys.proto.prototypehub.storage.assets.PrototypeAssetFiles.Snapshot;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest.FileEntry;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.request.AssetExportRequest;
import com.gaiys.proto.prototypehub.web.request.AssetQueryRequest;
import com.gaiys.proto.prototypehub.web.response.AssetPageResponse;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 内部固定修订资产查询与导出：当前query和项目READ取交集，不接受分享或预览凭据。 数据库授权先结束再做FileWork；无导出任务表/后台队列，四个请求级槽限制临时磁盘占用。 */
@Service
@RequiredArgsConstructor
public class PrototypeAssetService {
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeAssetFiles files;
    private final FileWork work;
    private final ObservationRegistry observations;
    private final Semaphore exports = new Semaphore(4);

    public Mono<AssetPageResponse> list(String version, String revision, AssetQueryRequest query) {
        return access.current()
                .flatMap(actor -> load(actor.userId(), version, revision))
                .map(snapshot -> page(snapshot, query));
    }

    private AssetPageResponse page(Snapshot snapshot, AssetQueryRequest query) {
        String search = query.getSearch() == null ? "" : query.getSearch().toLowerCase(Locale.ROOT);
        var filtered =
                snapshot.assets().stream()
                        .filter(file -> file.path().toLowerCase(Locale.ROOT).contains(search))
                        .toList();
        long offset = (long) query.getPageIndex() * query.getPageSize();
        var data = filtered.stream().skip(offset).limit(query.getPageSize()).toList();
        return new AssetPageResponse(
                snapshot.projectId(),
                snapshot.versionId(),
                snapshot.revisionId(),
                snapshot.manifestSha256(),
                query.getPageIndex(),
                query.getPageSize(),
                filtered.size(),
                PrototypeAssetFiles.bytes(filtered),
                snapshot.assets().size(),
                PrototypeAssetFiles.bytes(snapshot.assets()),
                data);
    }

    private Mono<Snapshot> load(String actor, String version, String revision) {
        return authorize(actor, version)
                .flatMap(row -> revisions.requireManifest(row, revision))
                .flatMap(
                        row ->
                                work.call(
                                        () ->
                                                Observation.createNotStarted(
                                                                "prototype.assets.manifest",
                                                                observations)
                                                        .observeChecked(
                                                                () -> files.snapshot(row))));
    }

    private Mono<com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity> authorize(
            String user, String version) {
        // getByUserId重取动作；账号状态、项目成员及删除状态由唯一范围服务逐次校验。
        return access.actor(user)
                .flatMap(
                        actor ->
                                actor.authentication()
                                                .hasPermission("prototype-hub-version", "query")
                                        ? access.requireVersion(actor, version, Action.READ, false)
                                        : Mono.error(PrototypeProjectAccessService.roleDenied()));
    }

    public Mono<Void> export(
            String version,
            String revision,
            AssetExportRequest input,
            Format format,
            ServerHttpResponse response) {
        return access.current()
                .flatMap(
                        actor ->
                                load(actor.userId(), version, revision)
                                        .flatMap(
                                                snapshot -> {
                                                    List<FileEntry> selected =
                                                            files.select(snapshot, input, format);
                                                    return transfer(
                                                            actor.userId(),
                                                            snapshot,
                                                            selected,
                                                            format,
                                                            response);
                                                }));
    }

    private Mono<Void> transfer(
            String actor,
            Snapshot snapshot,
            List<FileEntry> selected,
            Format format,
            ServerHttpResponse response) {
        AtomicBoolean released = new AtomicBoolean();
        return Mono.usingWhen(
                        Mono.fromSupplier(
                                () -> {
                                    if (!exports.tryAcquire())
                                        throw PrototypeAssetFiles.error("EXPORT_BUSY", 429);
                                    return files.attempt(snapshot, selected, format);
                                }),
                        attempt ->
                                work.run(attempt::open)
                                        // 每资源重取当前动作/范围；不在压缩期间持数据库事务或复用权限缓存。
                                        .thenMany(
                                                Flux.fromIterable(selected)
                                                        .concatMap(
                                                                entry ->
                                                                        authorize(
                                                                                        actor,
                                                                                        snapshot
                                                                                                .versionId())
                                                                                .then(
                                                                                        work.run(
                                                                                                () ->
                                                                                                        attempt
                                                                                                                .copy(
                                                                                                                        entry))),
                                                                1))
                                        .then(work.call(attempt::finish))
                                        .flatMap(
                                                path ->
                                                        send(
                                                                actor,
                                                                snapshot.versionId(),
                                                                path,
                                                                selected,
                                                                format,
                                                                response)),
                        attempt -> cleanup(attempt, released),
                        (attempt, failure) -> cleanup(attempt, released),
                        attempt -> {
                            attempt.cancel();
                            return cleanup(attempt, released);
                        })
                .checkpoint("prototype-hub/assets/export");
    }

    private Mono<Void> cleanup(PrototypeAssetFiles.Attempt attempt, AtomicBoolean released) {
        return work.run(attempt::close)
                .doFinally(
                        signal -> {
                            if (released.compareAndSet(false, true)) exports.release();
                        });
    }

    private Mono<Void> send(
            String actor,
            String version,
            Path path,
            List<FileEntry> selected,
            Format format,
            ServerHttpResponse response) {
        return work.call(() -> Files.size(path))
                .flatMap(
                        size ->
                                authorize(actor, version)
                                        .then(
                                                Mono.defer(
                                                        () -> {
                                                            String name =
                                                                    switch (format) {
                                                                        case FILE ->
                                                                                Path.of(
                                                                                                selected.getFirst()
                                                                                                        .path())
                                                                                        .getFileName()
                                                                                        .toString();
                                                                        case ZIP ->
                                                                                "prototype-assets.zip";
                                                                        case MANIFEST ->
                                                                                "prototype-assets.json";
                                                                    };
                                                            MediaType type =
                                                                    switch (format) {
                                                                        case FILE ->
                                                                                MediaType
                                                                                        .parseMediaType(
                                                                                                selected.getFirst()
                                                                                                        .mediaType());
                                                                        case ZIP ->
                                                                                MediaType
                                                                                        .parseMediaType(
                                                                                                "application/zip");
                                                                        case MANIFEST ->
                                                                                MediaType
                                                                                        .APPLICATION_JSON;
                                                                    };
                                                            response.getHeaders()
                                                                    .setContentType(type);
                                                            response.getHeaders()
                                                                    .setContentLength(size);
                                                            response.getHeaders()
                                                                    .setContentDisposition(
                                                                            ContentDisposition
                                                                                    .attachment()
                                                                                    .filename(
                                                                                            name,
                                                                                            StandardCharsets
                                                                                                    .UTF_8)
                                                                                    .build());
                                                            response.getHeaders()
                                                                    .set(
                                                                            HttpHeaders
                                                                                    .CACHE_CONTROL,
                                                                            "private, no-store");
                                                            response.getHeaders()
                                                                    .set(
                                                                            "X-Content-Type-Options",
                                                                            "nosniff");
                                                            response.getHeaders()
                                                                    .set(
                                                                            "Referrer-Policy",
                                                                            "no-referrer");
                                                            // usingWhen拥有整个异步响应，不能在返回Resource时先删除仍待发送的临时文件。
                                                            return response.writeWith(
                                                                    DataBufferUtils.read(
                                                                            path,
                                                                            response
                                                                                    .bufferFactory(),
                                                                            64 * 1024));
                                                        })));
    }
}
