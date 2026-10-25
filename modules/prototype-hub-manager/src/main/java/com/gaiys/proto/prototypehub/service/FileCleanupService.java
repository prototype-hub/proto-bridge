package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.FileCleanupEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewMessageEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
@AllArgsConstructor
public class FileCleanupService extends GenericReactiveCrudService<FileCleanupEntity, String> {
    private final PrototypeStorageService storage;
    private final ReactiveRepository<PrototypeRevisionEntity, String> revisions;
    private final ReactiveRepository<PrototypeVersionEntity, String> versions;
    private final ReactiveRepository<PrototypeReviewMessageEntity, String> messages;

    /** 只登记已确认未提交归属的上传候选；保留期内的业务删除不得调用文件清理。 */
    public Mono<FileCleanupEntity> enqueueCandidate(String project, String version, String path) {
        FileCleanupEntity task = new FileCleanupEntity();
        task.setId(PrototypeShareService.random());
        task.setProjectId(SafePaths.id(project));
        task.setVersionId(SafePaths.id(version));
        task.setResourceType("upload-candidate");
        task.setStoragePath(path);
        task.setCreateTime(System.currentTimeMillis());
        task.setNextAttempt(0L);
        task.setAttempts(0);
        return insert(task).thenReturn(task);
    }

    /** 调用者持项目/版本锁，在删除引用的同一事务中登记；同一目录只保留一个到期任务。 */
    public Mono<FileCleanupEntity> enqueueExpired(String project, String version, String path) {
        String canonical = SafePaths.canonicalSitePath(project, path);
        FileCleanupEntity task = new FileCleanupEntity();
        task.setId(
                HexFormat.of()
                        .formatHex(
                                PrototypeCreationReceiptService.sha256()
                                        .digest(canonical.getBytes(StandardCharsets.UTF_8))));
        task.setProjectId(project);
        task.setVersionId(SafePaths.id(version));
        task.setResourceType("expired-snapshot");
        task.setStoragePath(canonical);
        task.setCreateTime(System.currentTimeMillis());
        task.setNextAttempt(0L);
        task.setAttempts(0);
        return findById(task.getId())
                .switchIfEmpty(Mono.defer(() -> insert(task).thenReturn(task)));
    }

    /** 永久删除消息时与元数据删除同事务登记；候选回滚的物理删除失败也复用此队列。 */
    public Mono<Void> enqueueReviewImage(String project, String version, String path) {
        String canonical = SafePaths.canonicalReviewImagePath(project, path);
        FileCleanupEntity task = new FileCleanupEntity();
        task.setId(
                HexFormat.of()
                        .formatHex(
                                PrototypeCreationReceiptService.sha256()
                                        .digest(canonical.getBytes(StandardCharsets.UTF_8))));
        task.setProjectId(SafePaths.id(project));
        task.setVersionId(SafePaths.id(version));
        task.setResourceType("review-image");
        task.setStoragePath(canonical);
        task.setCreateTime(System.currentTimeMillis());
        task.setNextAttempt(0L);
        task.setAttempts(0);
        return findById(task.getId())
                .switchIfEmpty(Mono.defer(() -> insert(task).thenReturn(task)))
                .then();
    }

    /** 软删行仍是保留引用；不使用业务侧的活动查询。 */
    private Mono<Boolean> referenced(FileCleanupEntity task) {
        Path target = Path.of(task.getStoragePath());
        String storage = target.getParent().getFileName().toString();
        // 规范目录的最后一段必然出现在所有词法别名中；数据库先收窄，流式比较避免装入全部历史。
        String candidate = "%/" + storage + "/%";
        return versions.createQuery()
                .select(PrototypeVersionEntity::getStoragePath)
                .like(PrototypeVersionEntity::getStoragePath, candidate)
                .fetch()
                .any(version -> Path.of(version.getStoragePath()).normalize().equals(target))
                .flatMap(
                        found ->
                                found
                                        ? Mono.just(true)
                                        : revisions
                                                .createQuery()
                                                .select(PrototypeRevisionEntity::getStoragePath)
                                                .like(
                                                        PrototypeRevisionEntity::getStoragePath,
                                                        candidate)
                                                .fetch()
                                                .any(
                                                        revision ->
                                                                Path.of(revision.getStoragePath())
                                                                        .normalize()
                                                                        .equals(target)));
    }

    public Mono<Void> attempt(FileCleanupEntity task) {
        // 新快照目录永久唯一、不复用；迁移只引用已由 p_version 保护的目录。
        // 引用存在时保留任务及文件并退避，避免历史数据或误入队任务被物理删除。
        Mono<Void> deletion =
                Mono.defer(
                        () -> {
                            if ("review-image".equals(task.getResourceType())) {
                                SafePaths.canonicalReviewImagePath(
                                        task.getProjectId(), task.getStoragePath());
                                // 包括软删资源在内的消息仍是保留引用；上传目录永久随机且不复用。
                                return messages.createQuery()
                                        .where(
                                                PrototypeReviewMessageEntity::getImagePath,
                                                task.getStoragePath())
                                        .count()
                                        .flatMap(
                                                count ->
                                                        count > 0
                                                                ? Mono.error(
                                                                        new IllegalStateException(
                                                                                "Referenced review image"))
                                                                : storage.deleteReviewImage(
                                                                        task.getProjectId(),
                                                                        task.getStoragePath()));
                            }
                            // 旧 project/version 任务混合了已删除历史和候选，来源不明时只能保留。
                            if (!"upload-candidate".equals(task.getResourceType())
                                    && !"expired-snapshot".equals(task.getResourceType()))
                                return Mono.error(
                                        new IllegalStateException(
                                                "Unclassified retained cleanup task"));
                            String canonical =
                                    SafePaths.canonicalSitePath(
                                            task.getProjectId(), task.getStoragePath());
                            if (!canonical.equals(task.getStoragePath()))
                                return Mono.error(
                                        new IllegalStateException("Noncanonical cleanup target"));
                            return referenced(task)
                                    .flatMap(
                                            live -> {
                                                if (live)
                                                    return Mono.error(
                                                            new IllegalStateException(
                                                                    "Referenced prototype"
                                                                            + " storage"));
                                                return storage.deleteStoredVersion(
                                                        task.getProjectId(),
                                                        task.getStoragePath(),
                                                        task.getVersionId());
                                            });
                        });
        return deletion.then(deleteById(task.getId()))
                .then()
                .onErrorResume(
                        error -> {
                            log.warn(
                                    "File cleanup pending: task={}, project={}, result={},"
                                            + " errorType={}",
                                    task.getId(),
                                    task.getProjectId(),
                                    "retry",
                                    error.getClass().getSimpleName());
                            int attempts = task.getAttempts() + 1;
                            return createUpdate()
                                    .set(FileCleanupEntity::getAttempts, attempts)
                                    .set(
                                            FileCleanupEntity::getNextAttempt,
                                            System.currentTimeMillis()
                                                    + Math.min(
                                                            3_600_000L,
                                                            1_000L
                                                                    * (1L
                                                                            << Math.min(
                                                                                    attempts, 12))))
                                    .where(FileCleanupEntity::getId, task.getId())
                                    .execute()
                                    .then()
                                    .onErrorResume(
                                            databaseError -> {
                                                log.error(
                                                        "Unable to update cleanup retry state:"
                                                                + " task={}",
                                                        task.getId());
                                                return Mono.empty();
                                            });
                        });
    }

    public Mono<Void> retryPending() {
        return Mono.defer(
                () ->
                        createQuery()
                                .lte(FileCleanupEntity::getNextAttempt, System.currentTimeMillis())
                                .orderBy(
                                        SortOrder.asc(FileCleanupEntity::getNextAttempt),
                                        SortOrder.asc(FileCleanupEntity::getId))
                                .paging(0, 50)
                                .fetch()
                                .collectList()
                                // 先消费并释放查询连接，再逐任务 I/O/更新；pool=1 不在游标内等待写连接。
                                .flatMapMany(Flux::fromIterable)
                                .concatMap(this::attempt, 1)
                                .then());
    }
}
