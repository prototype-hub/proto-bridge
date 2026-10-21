package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.FileCleanupEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeStorageService.UploadPaths;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Predicate;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

/**
 * 协调原型 ZIP 接收、解压和版本持久化。
 *
 * <p>每次订阅独立创建上传目录和事务结果信号；usingWhen 等待数据库最终结果决定目录归属， 提交后的取消不会删除已生效的文件，旧目录通过持久化清理任务处理。
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
    private final PrototypeCreationReceiptService receipts;
    private final PrototypeProjectAccessService access;

    public Mono<PrototypeVersionEntity> upload(
            String project, String name, String description, FilePart file, String actor) {
        return upload(project, name, description, file, actor, null);
    }

    /** 可选请求键仅影响创建重试；未提供的已发布客户端保持原有创建语义。 */
    public Mono<PrototypeVersionEntity> upload(
            String project,
            String name,
            String description,
            FilePart file,
            String actor,
            String key) {
        return Mono.defer(
                () -> {
                    validate(name, description, file);
                    var request =
                            receipts.request(
                                    actor,
                                    "create-version",
                                    project,
                                    key,
                                    name.trim(),
                                    description);
                    String id = PrototypeShareService.random();
                    UploadAttempt attempt = new UploadAttempt();
                    // 带键请求的回执可能指向已删除资源，须在读取完整指纹后统一返回幂等冲突。
                    Mono<Void> existingProject =
                            request == null
                                    ? access.requireProject(
                                                    project,
                                                    PrototypeProjectAccessService.Action.EDIT)
                                            .then()
                                    : projects.findById(project)
                                            .flatMap(
                                                    found ->
                                                            access.requireProject(
                                                                    project,
                                                                    PrototypeProjectAccessService
                                                                            .Action.EDIT))
                                            .then();
                    return existingProject.then(
                            Mono.usingWhen(
                                    storage.prepareUpload(project, id),
                                    paths ->
                                            receiveAndCreate(
                                                    project,
                                                    id,
                                                    name,
                                                    description,
                                                    file,
                                                    actor,
                                                    request,
                                                    paths,
                                                    attempt),
                                    // 重放返回旧资源时本次目录不归任何版本所有，成功路径同样回收。
                                    paths -> cleanup(project, id, attempt),
                                    (paths, error) -> cleanup(project, id, attempt),
                                    paths -> cleanup(project, id, attempt)));
                });
    }

    public Mono<PrototypeVersionEntity> update(
            String id, String name, String description, FilePart file) {
        return Mono.defer(
                () -> {
                    validate(name, description, file);
                    return access.requireVersion(id, PrototypeProjectAccessService.Action.EDIT)
                            .flatMap(
                                    old -> updateExistingVersion(id, old, name, description, file));
                });
    }

    private Mono<String> receiveAndExtract(FilePart file, UploadPaths paths) {
        // 接收完成后才解压，阻塞 ZIP 操作沿用 FileWork 的专用调度边界。
        return storage.receive(file, paths.zip())
                .then(work.call(() -> extractor.extract(paths.zip(), paths.site())));
    }

    /** 接收、解压和散列均先完成，再进入持锁的短事务。 */
    private Mono<PrototypeVersionEntity> receiveAndCreate(
            String project,
            String id,
            String name,
            String description,
            FilePart file,
            String actor,
            PrototypeCreationReceiptService.Request request,
            UploadPaths paths,
            UploadAttempt attempt) {
        return receiveAndExtract(file, paths)
                .zipWhen(entry -> payloadDigest(request, paths))
                .flatMap(
                        received -> {
                            var completeRequest =
                                    request == null ? null : request.withPayload(received.getT2());
                            return createAndPersistVersion(
                                    project,
                                    id,
                                    name,
                                    description,
                                    received.getT1(),
                                    actor,
                                    attempt,
                                    completeRequest);
                        });
    }

    private Mono<String> payloadDigest(
            PrototypeCreationReceiptService.Request request, UploadPaths paths) {
        if (request == null) return Mono.just("");
        // 原始 ZIP 流式散列在专用文件线程执行，不聚合上传内容到内存，不占用数据库锁。
        return work.call(
                () -> {
                    var digest = PrototypeCreationReceiptService.sha256();
                    try (var input =
                            new DigestInputStream(Files.newInputStream(paths.zip()), digest)) {
                        input.transferTo(OutputStream.nullOutputStream());
                    }
                    return HexFormat.of().formatHex(digest.digest());
                });
    }

    private Mono<PrototypeVersionEntity> createAndPersistVersion(
            String project,
            String id,
            String name,
            String description,
            String entry,
            String actor,
            UploadAttempt attempt,
            PrototypeCreationReceiptService.Request request) {
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
        // 只在解压完成后持项目行锁，回执、实际计数和 INSERT 共用一个短事务及 Spring 连接。
        Mono<PrototypeVersionEntity> creation =
                database.sql("select id from p_project where id=:id for update")
                        .bind("id", project)
                        .fetch()
                        .one()
                        .map(lock -> true)
                        .defaultIfEmpty(false)
                        .flatMap(
                                exists ->
                                        exists
                                                ? access.requireProject(
                                                                project,
                                                                PrototypeProjectAccessService.Action
                                                                        .EDIT)
                                                        .then(
                                                                replayOrCreateVersion(
                                                                        true, version, request))
                                                : replayOrCreateVersion(false, version, request));
        return inUploadTransaction(attempt, creation, result -> id.equals(result.getId()));
    }

    private Mono<PrototypeVersionEntity> replayOrCreateVersion(
            boolean projectExists,
            PrototypeVersionEntity version,
            PrototypeCreationReceiptService.Request request) {
        return receipts.replay(
                        request,
                        id ->
                                versions.findById(id)
                                        .flatMap(
                                                versionFound ->
                                                        access.requireVersion(
                                                                id,
                                                                PrototypeProjectAccessService.Action
                                                                        .EDIT)))
                .switchIfEmpty(
                        Mono.defer(() -> insertWithinCapacity(projectExists, version, request)));
    }

    private Mono<PrototypeVersionEntity> insertWithinCapacity(
            boolean projectExists,
            PrototypeVersionEntity version,
            PrototypeCreationReceiptService.Request request) {
        // 先重放回执，再判断项目是否存在，已删除项目下的原请求仍返回幂等冲突。
        if (!projectExists) return Mono.error(PrototypeHubException.missing("project"));
        return versions.requireCapacity(version.getProjectId())
                .then(versions.insert(version))
                .then(receipts.record(request, version.getId()))
                .thenReturn(version);
    }

    private Mono<PrototypeVersionEntity> updateExistingVersion(
            String id, PrototypeVersionEntity old, String name, String description, FilePart file) {
        String replacement = id + "-update-" + PrototypeShareService.random();
        UploadAttempt attempt = new UploadAttempt();
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
                                                        attempt))
                                .flatMap(
                                        pair ->
                                                cleanup.attempt(pair.getT2())
                                                        .thenReturn(pair.getT1())),
                paths -> cleanup(old.getProjectId(), replacement, attempt),
                (paths, error) -> cleanup(old.getProjectId(), replacement, attempt),
                paths -> cleanup(old.getProjectId(), replacement, attempt));
    }

    private Mono<Tuple2<PrototypeVersionEntity, FileCleanupEntity>> replaceVersionAndEnqueueCleanup(
            PrototypeVersionEntity old,
            String id,
            String name,
            String description,
            String replacement,
            String entry,
            UploadAttempt attempt) {
        // 版本切换与旧目录清理任务原子提交，文件归属由事务最终完成状态决定。
        Mono<Tuple2<PrototypeVersionEntity, FileCleanupEntity>> replacementResult =
                access.lockProject(old.getProjectId())
                        .then(
                                database.sql("select id from p_version where id=:id for update")
                                        .bind("id", id)
                                        .fetch()
                                        .one()
                                        .switchIfEmpty(
                                                Mono.error(
                                                        () ->
                                                                PrototypeHubException.missing(
                                                                        "version"))))
                        // 上传接收期间可能被撤员；实际切换目录之前，在项目锁内重新检查当前授权。
                        .then(access.requireVersion(id, PrototypeProjectAccessService.Action.EDIT))
                        .then(
                                versions.replace(
                                        old,
                                        name,
                                        description,
                                        storage.relativeSitePath(old.getProjectId(), replacement),
                                        entry))
                        .flatMap(
                                updated ->
                                        cleanup.enqueue(
                                                        old.getProjectId(),
                                                        id,
                                                        old.getStoragePath())
                                                .map(task -> Tuples.of(updated, task)));
        return inUploadTransaction(attempt, replacementResult, result -> true);
    }

    /**
     * 当前上传入口拥有独立物理事务，不能随意再套外层 REQUIRED 事务，否则目录清理会等待外层提交。 同步在首个 SQL 前登记，内层取消与外层清理共用登记握手，避免回滚之后才登记同步。
     */
    private <T> Mono<T> inUploadTransaction(
            UploadAttempt attempt, Mono<T> operation, Predicate<T> candidateOwned) {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .flatMap(
                        manager ->
                                attempt.enlist(manager)
                                        ? operation.doOnNext(
                                                result ->
                                                        attempt.candidateOwned(
                                                                candidateOwned.test(result)))
                                        : Mono.empty())
                .doOnCancel(attempt::closeBeforeEnlistment)
                .as(transactions::transactional);
    }

    private Mono<Void> cleanup(String project, String storageId, UploadAttempt attempt) {
        return Mono.defer(
                () -> {
                    // 接收/解压失败或事务尚未开始时也能结束归属判断；已登记则必须等 afterCompletion。
                    attempt.closeBeforeEnlistment();
                    return attempt.completion
                            .asMono()
                            .flatMap(
                                    outcome ->
                                            cleanupAfterTransaction(project, storageId, outcome));
                });
    }

    private Mono<Void> cleanupAfterTransaction(
            String project, String storageId, UploadOutcome outcome) {
        if (outcome.status() == TransactionSynchronization.STATUS_UNKNOWN) {
            // 提交结果未知时禁止推断回滚，以免删除可能已被数据库引用的文件。
            log.error(
                    "Upload transaction outcome is unknown; retaining files: project={}, storage={}",
                    project,
                    storageId);
            return Mono.empty();
        }
        if (outcome.status() == TransactionSynchronization.STATUS_COMMITTED
                && outcome.candidateOwned()) {
            return Mono.empty();
        }
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

    /** 单次上传的有限生命周期状态，无常驻缓存；结果不可变且只在数据库完成后发布。 */
    private record UploadOutcome(int status, boolean candidateOwned) {
        private static final int NO_DB_WRITE = -1;
    }

    /** 每次订阅独占的上传状态；协调事务登记与取消，仅在确认最终结果后释放候选目录。 */
    private static final class UploadAttempt {
        private final Sinks.One<UploadOutcome> completion = Sinks.one();
        private boolean enlisted;
        private boolean closed;
        private boolean candidateOwned;

        private synchronized boolean enlist(TransactionSynchronizationManager manager) {
            if (closed) return false;
            // 只在此极短状态门内登记内存同步，不执行 I/O，也不等待响应式信号。
            manager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public Mono<Void> afterCompletion(int status) {
                            completed(status);
                            return Mono.empty();
                        }
                    });
            enlisted = true;
            return true;
        }

        private synchronized void candidateOwned(boolean owned) {
            // 只表示业务结果引用本次新目录；此处不声称事务已经提交。
            candidateOwned = owned;
        }

        private void closeBeforeEnlistment() {
            boolean noDatabaseWrite;
            synchronized (this) {
                noDatabaseWrite = !enlisted;
                if (noDatabaseWrite) closed = true;
            }
            if (noDatabaseWrite) {
                completion.tryEmitValue(new UploadOutcome(UploadOutcome.NO_DB_WRITE, false));
            }
        }

        private void completed(int status) {
            UploadOutcome outcome;
            synchronized (this) {
                outcome = new UploadOutcome(status, candidateOwned);
            }
            // 不在事务同步中等清理结束；usingWhen 在事务外订阅结果并处理目录。
            completion.tryEmitValue(outcome);
        }
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
