package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeStorageService.UploadPaths;
import com.gaiys.proto.prototypehub.storage.builder.PrototypeManifestBuilder;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 协调原型 ZIP 接收、解压和版本持久化。
 *
 * <p>每次订阅独立创建上传目录和事务结果信号；usingWhen 等待数据库最终结果决定目录归属， 提交后的取消不会删除已生效的文件；历史目录持续保留，整体删除通过持久化任务清理。
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
    private final PrototypeCreationReceiptService receipts;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeManifestBuilder manifests;

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
                                    Mono.just(attempt),
                                    acquired ->
                                            prepareUpload(project, id, acquired)
                                                    .flatMap(
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
                                                                            acquired)),
                                    // 重放返回旧资源时本次目录不归任何版本所有，成功路径同样回收。
                                    paths -> cleanup(project, id, attempt),
                                    (paths, error) -> cleanup(project, id, attempt),
                                    paths -> cleanup(project, id, attempt)));
                });
    }

    public Mono<PrototypeVersionEntity> update(
            String id, String name, String description, FilePart file) {
        return update(id, name, description, file, null);
    }

    /** 可选客户端前置条件；缺省同样使用上传前快照比较，不能后到覆盖先提交的结果。 */
    public Mono<PrototypeVersionEntity> update(
            String id, String name, String description, FilePart file, String expectedRevisionId) {
        return Mono.defer(
                () -> {
                    validate(name, description, file);
                    return access.requireVersion(id, PrototypeProjectAccessService.Action.EDIT)
                            .flatMap(old -> revisions.current(old).thenReturn(old))
                            .flatMap(
                                    old -> {
                                        if (expectedRevisionId != null
                                                && !expectedRevisionId.equals(
                                                        old.getCurrentRevisionId()))
                                            return Mono.error(versionConflict());
                                        return access.current()
                                                .flatMap(
                                                        actor ->
                                                                updateExistingVersion(
                                                                        id,
                                                                        old,
                                                                        name,
                                                                        description,
                                                                        file,
                                                                        actor.userId()));
                                    });
                });
    }

    private Mono<UploadPaths> prepareUpload(
            String project, String storageId, UploadAttempt attempt) {
        return work.call(
                () -> {
                    if (!attempt.beginWriting()) throw new CancellationException();
                    try {
                        return storage.prepareUploadNow(project, storageId, attempt::ownDirectory);
                    } finally {
                        attempt.completeWriting();
                    }
                });
    }

    private Mono<String> receiveAndExtract(
            FilePart file, UploadPaths paths, UploadAttempt attempt) {
        // 接收完成后才解压，阻塞 ZIP 操作沿用 FileWork 的专用调度边界。
        return storage.receive(file, paths.zip())
                .then(
                        work.call(
                                () -> {
                                    // 取消仅会 interrupt 调度线程；ZIP/文件库可能继续写，必须等物理 finally 才删除目录。
                                    if (!attempt.beginWriting()) throw new CancellationException();
                                    try {
                                        return extractor.extract(paths.zip(), paths.site());
                                    } finally {
                                        attempt.completeWriting();
                                    }
                                }));
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
        return receiveAndExtract(file, paths, attempt)
                .flatMap(
                        entry ->
                                work.call(() -> manifests.build(paths.site(), entry))
                                        .zipWith(payloadDigest(request, paths))
                                        .flatMap(
                                                received ->
                                                        createAndPersistVersion(
                                                                project,
                                                                id,
                                                                name,
                                                                description,
                                                                entry,
                                                                actor,
                                                                attempt,
                                                                request == null
                                                                        ? null
                                                                        : request.withPayload(
                                                                                received.getT2()),
                                                                received.getT1())));
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
            PrototypeCreationReceiptService.Request request,
            PrototypeManifestBuilder.Built manifest) {
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
        version.setCurrentRevisionId(PrototypeShareService.random());
        PrototypeRevisionEntity revision =
                revisions.candidate(version, version.getCurrentRevisionId(), manifest);
        // 只在解压完成后持项目行锁，回执、实际计数和 INSERT 共用一个短事务及 Spring 连接。
        Mono<PrototypeVersionEntity> creation =
                access.tryLockProject(project)
                        .flatMap(
                                exists ->
                                        exists
                                                ? access.requireProject(
                                                                project,
                                                                PrototypeProjectAccessService.Action
                                                                        .EDIT)
                                                        .then(
                                                                replayOrCreateVersion(
                                                                        true, version, revision,
                                                                        request))
                                                : replayOrCreateVersion(
                                                        false, version, revision, request));
        return inUploadTransaction(attempt, creation, result -> id.equals(result.getId()));
    }

    private Mono<PrototypeVersionEntity> replayOrCreateVersion(
            boolean projectExists,
            PrototypeVersionEntity version,
            PrototypeRevisionEntity revision,
            PrototypeCreationReceiptService.Request request) {
        return receipts.replayReceipt(
                        request,
                        receipt ->
                                versions.findById(receipt.getResourceId())
                                        .flatMap(
                                                found ->
                                                        access.requireVersion(
                                                                found.getId(),
                                                                PrototypeProjectAccessService.Action
                                                                        .EDIT))
                                        .flatMap(
                                                found ->
                                                        revisions
                                                                .require(
                                                                        found,
                                                                        receipt.getRevisionId())
                                                                .map(
                                                                        original -> {
                                                                            PrototypeVersionEntity
                                                                                    response =
                                                                                            found
                                                                                                    .copyTo(
                                                                                                            new PrototypeVersionEntity());
                                                                            response
                                                                                    .setResponseRevisionId(
                                                                                            original
                                                                                                    .getId());
                                                                            response.setVersionName(
                                                                                    original
                                                                                            .getVersionName());
                                                                            response.setDescription(
                                                                                    original
                                                                                            .getDescription());
                                                                            response.setEntryFile(
                                                                                    original
                                                                                            .getEntryFile());
                                                                            return response;
                                                                        })))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        insertWithinCapacity(
                                                projectExists, version, revision, request)));
    }

    private Mono<PrototypeVersionEntity> insertWithinCapacity(
            boolean projectExists,
            PrototypeVersionEntity version,
            PrototypeRevisionEntity revision,
            PrototypeCreationReceiptService.Request request) {
        if (!projectExists) return Mono.error(PrototypeHubException.missing("project"));
        return versions.requireCapacity(version.getProjectId())
                .then(versions.insert(version))
                .then(revisions.insert(revision))
                .then(receipts.record(request, version.getId(), revision.getId()))
                .thenReturn(version);
    }

    private Mono<PrototypeVersionEntity> updateExistingVersion(
            String id,
            PrototypeVersionEntity old,
            String name,
            String description,
            FilePart file,
            String actor) {
        String revisionId = PrototypeShareService.random();
        String replacement = id + "-revision-" + revisionId;
        UploadAttempt attempt = new UploadAttempt();
        return Mono.usingWhen(
                Mono.just(attempt),
                acquired ->
                        prepareUpload(old.getProjectId(), replacement, acquired)
                                .flatMap(
                                        paths ->
                                                receiveAndExtract(file, paths, acquired)
                                                        .flatMap(
                                                                entry ->
                                                                        work.call(
                                                                                        () ->
                                                                                                manifests
                                                                                                        .build(
                                                                                                                paths
                                                                                                                        .site(),
                                                                                                                entry))
                                                                                .flatMap(
                                                                                        manifest ->
                                                                                                replaceVersion(
                                                                                                        old,
                                                                                                        name,
                                                                                                        description,
                                                                                                        replacement,
                                                                                                        entry,
                                                                                                        revisionId,
                                                                                                        actor,
                                                                                                        manifest,
                                                                                                        acquired)))),
                paths -> cleanup(old.getProjectId(), replacement, attempt),
                (paths, error) -> cleanup(old.getProjectId(), replacement, attempt),
                paths -> cleanup(old.getProjectId(), replacement, attempt));
    }

    private Mono<PrototypeVersionEntity> replaceVersion(
            PrototypeVersionEntity old,
            String name,
            String description,
            String replacement,
            String entry,
            String revisionId,
            String actor,
            PrototypeManifestBuilder.Built manifest,
            UploadAttempt attempt) {
        // 项目→版本行锁；CAS 当前指针和路径防止并发覆盖。只发布 ready 快照，失败候选不进入历史。
        // 新快照与当前投影同一短事务提交，旧目录持续被旧 revision 引用，替换不得登记清理。
        Mono<PrototypeVersionEntity> result =
                access.lockProject(old.getProjectId())
                        .then(access.lockVersion(old.getId()))
                        .then(
                                access.requireVersion(
                                        old.getId(), PrototypeProjectAccessService.Action.EDIT))
                        .then(
                                versions.replace(
                                        old,
                                        name,
                                        description,
                                        storage.relativeSitePath(old.getProjectId(), replacement),
                                        entry,
                                        revisionId))
                        .flatMap(
                                updated -> {
                                    PrototypeRevisionEntity revision =
                                            revisions.candidate(updated, revisionId, manifest);
                                    revision.setCreatorId(actor);
                                    return revisions.insert(revision).thenReturn(updated);
                                });
        return inUploadTransaction(attempt, result, updated -> true);
    }

    private static PrototypeHubException versionConflict() {
        return new PrototypeHubException(
                "error.prototype-hub.version_conflict", "PROTOTYPE_HUB_VERSION_CONFLICT", 409);
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
                    return attempt.closeWriting()
                            .then(attempt.completion.asMono())
                            .flatMap(
                                    outcome ->
                                            attempt.ownsDirectory()
                                                    ? cleanupAfterTransaction(
                                                            project, storageId, outcome)
                                                    : Mono.empty());
                });
    }

    private Mono<Void> cleanupAfterTransaction(
            String project, String storageId, UploadOutcome outcome) {
        if (outcome.status() == TransactionSynchronization.STATUS_UNKNOWN) {
            // 提交结果未知时禁止推断回滚，以免删除可能已被数据库引用的文件。
            log.error(
                    "Upload transaction outcome is unknown; retaining files: project={},"
                            + " storage={}",
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
                            // 已确认不属于任何已提交快照的候选目录也登记持久重试，进程重启后可收敛。
                            return cleanup.enqueue(
                                            project,
                                            storageId,
                                            storage.relativeSitePath(project, storageId))
                                    .then()
                                    .onErrorResume(
                                            databaseError -> {
                                                log.error(
                                                        "Unable to persist upload cleanup task:"
                                                                + " project={}, storage={}",
                                                        project,
                                                        storageId);
                                                return Mono.empty();
                                            });
                        });
    }

    /** 单次上传的有限生命周期状态，无常驻缓存；结果不可变且只在数据库完成后发布。 */
    private record UploadOutcome(int status, boolean candidateOwned) {
        private static final int NO_DB_WRITE = -1;
    }

    /** 每次订阅独占的上传状态；协调事务登记与取消，仅在确认最终结果后释放候选目录。 */
    private static final class UploadAttempt {
        private final Sinks.One<UploadOutcome> completion = Sinks.one();
        private final Sinks.One<Void> writeCompletion = Sinks.one();
        private int activeWrites;
        private boolean writeClosed;
        private boolean directoryOwned;

        private synchronized boolean beginWriting() {
            if (writeClosed) return false;
            activeWrites++;
            return true;
        }

        private synchronized void ownDirectory() {
            directoryOwned = true;
        }

        private void completeWriting() {
            boolean complete;
            synchronized (this) {
                activeWrites--;
                complete = writeClosed && activeWrites == 0;
            }
            if (complete) writeCompletion.tryEmitEmpty();
        }

        private Mono<Void> closeWriting() {
            boolean complete;
            synchronized (this) {
                writeClosed = true;
                complete = activeWrites == 0;
            }
            if (complete) writeCompletion.tryEmitEmpty();
            return writeCompletion.asMono();
        }

        private synchronized boolean ownsDirectory() {
            return directoryOwned;
        }

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
