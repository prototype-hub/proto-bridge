package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.FileCleanupEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeStorageService.UploadPaths;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

/**
 * 协调原型 ZIP 接收、解压和版本持久化。
 *
 * <p>每次订阅独立创建上传目录与提交标记；事务提交前的错误或取消由 usingWhen 回收临时文件， 事务提交后保留新目录，旧目录通过持久化清理任务处理。
 */
@Service
@Slf4j
@AllArgsConstructor
public class PrototypeUploadService {
    private final PrototypeProjectService projects;
    private final PrototypeVersionService versions;
    private final PrototypeStorageService storage;
    private final SecureZipExtractor extractor;
    private final FileWork work;
    private final TransactionalOperator transactions;
    private final FileCleanupService cleanup;
    private final DatabaseClient database;

    public Mono<PrototypeVersionEntity> upload(
            String project, String name, String description, FilePart file, String actor) {
        return Mono.defer(
                () -> {
                    validate(name, description, file);
                    String id = PrototypeShareService.random();
                    AtomicBoolean committed = new AtomicBoolean();
                    return projects.requireById(project)
                            .then(
                                    Mono.usingWhen(
                                            storage.prepareUpload(project, id),
                                            paths ->
                                                    receiveAndExtract(file, paths)
                                                            .flatMap(
                                                                    entry ->
                                                                            createAndPersistVersion(
                                                                                    project,
                                                                                    id,
                                                                                    name,
                                                                                    description,
                                                                                    entry,
                                                                                    actor,
                                                                                    committed)),
                                            paths -> Mono.empty(),
                                            (paths, error) -> cleanup(project, id, committed.get()),
                                            paths -> cleanup(project, id, committed.get())));
                });
    }

    public Mono<PrototypeVersionEntity> update(
            String id, String name, String description, FilePart file) {
        return Mono.defer(
                () -> {
                    validate(name, description, file);
                    return versions.requireById(id)
                            .flatMap(
                                    old -> updateExistingVersion(id, old, name, description, file));
                });
    }

    private Mono<String> receiveAndExtract(FilePart file, UploadPaths paths) {
        // 接收完成后才解压，阻塞 ZIP 操作沿用 FileWork 的专用调度边界。
        return storage.receive(file, paths.zip())
                .then(work.call(() -> extractor.extract(paths.zip(), paths.site())));
    }

    private Mono<PrototypeVersionEntity> createAndPersistVersion(
            String project,
            String id,
            String name,
            String description,
            String entry,
            String actor,
            AtomicBoolean committed) {
        PrototypeVersionEntity version = new PrototypeVersionEntity();
        version.setId(id);
        version.setProjectId(project);
        version.setVersionName(name.trim());
        version.setDescription(description);
        version.setStoragePath(storage.relativeSitePath(project, id));
        version.setEntryFile(entry);
        version.setPreviewKey(PrototypeShareService.random());
        version.setStatus("ready");
        version.setCreatorId(actor);
        version.setCreateTime(System.currentTimeMillis());
        // 项目行锁与版本插入沿用同一事务，提交后再标记新目录应被保留。
        return database.sql("select id from p_project where id=:id for update")
                .bind("id", project)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("project")))
                .then(versions.insert(version))
                .as(transactions::transactional)
                .doOnSuccess(rows -> committed.set(true))
                .thenReturn(version);
    }

    private Mono<PrototypeVersionEntity> updateExistingVersion(
            String id, PrototypeVersionEntity old, String name, String description, FilePart file) {
        String replacement = id + "-update-" + PrototypeShareService.random();
        AtomicBoolean committed = new AtomicBoolean();
        return Mono.usingWhen(
                storage.prepareUpload(old.getProjectId(), replacement),
                paths ->
                        receiveAndExtract(file, paths)
                                .flatMap(
                                        entry ->
                                                replaceVersionAndEnqueueCleanup(
                                                        old,
                                                        id,
                                                        name,
                                                        description,
                                                        replacement,
                                                        entry,
                                                        committed))
                                .flatMap(
                                        pair ->
                                                cleanup.attempt(pair.getT2())
                                                        .thenReturn(pair.getT1())),
                paths -> Mono.empty(),
                (paths, error) -> cleanup(old.getProjectId(), replacement, committed.get()),
                paths -> cleanup(old.getProjectId(), replacement, committed.get()));
    }

    private Mono<Tuple2<PrototypeVersionEntity, FileCleanupEntity>> replaceVersionAndEnqueueCleanup(
            PrototypeVersionEntity old,
            String id,
            String name,
            String description,
            String replacement,
            String entry,
            AtomicBoolean committed) {
        // 版本切换与旧目录清理任务沿用同一事务，提交标记保护已生效的新目录。
        return versions.replace(
                        old,
                        name,
                        description,
                        storage.relativeSitePath(old.getProjectId(), replacement),
                        entry)
                .flatMap(
                        updated ->
                                cleanup.enqueue(old.getProjectId(), id, old.getStoragePath())
                                        .map(task -> Tuples.of(updated, task)))
                .as(transactions::transactional)
                .doOnSuccess(value -> committed.set(true));
    }

    private Mono<Void> cleanup(String project, String storageId, boolean committed) {
        if (committed) return Mono.empty();
        return storage.deleteVersion(project, storageId)
                .onErrorResume(
                        error -> {
                            log.error(
                                    "Upload files require cleanup: project={}, storage={}",
                                    project,
                                    storageId);
                            return Mono.empty();
                        });
    }

    private static void validate(String name, String description, FilePart file) {
        if (name == null || name.isBlank() || name.trim().length() > 128)
            throw PrototypeHubException.badRequest(
                    "error.prototype-hub.version_name_invalid",
                    "PROTOTYPE_HUB_VERSION_NAME_INVALID");
        // 在接收和解压文件前检查持久化列的边界；省略说明仍保持可选语义。
        if (description != null && description.length() > 2000)
            throw PrototypeHubException.badRequest(
                    "error.prototype-hub.version_description_invalid",
                    "PROTOTYPE_HUB_VERSION_DESCRIPTION_INVALID");
        if (file == null || !file.filename().toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw PrototypeHubException.badRequest(
                    "error.prototype-hub.zip_required", "PROTOTYPE_HUB_ZIP_REQUIRED");
    }
}
