package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.FileCleanupEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
@AllArgsConstructor
public class FileCleanupService extends GenericReactiveCrudService<FileCleanupEntity, String> {
    private final PrototypeStorageService storage;
    private final ReactiveRepository<PrototypeRevisionEntity, String> revisions;
    private final ReactiveRepository<PrototypeVersionEntity, String> versions;
    private final ReactiveRepository<PrototypeProjectEntity, String> projects;
    private static final int HISTORY_BATCH = 100;

    public Mono<FileCleanupEntity> enqueue(String project, String version, String path) {
        FileCleanupEntity task = new FileCleanupEntity();
        task.setId(PrototypeShareService.random());
        task.setProjectId(SafePaths.id(project));
        task.setVersionId(version);
        task.setResourceType(version == null ? "project" : "version");
        task.setStoragePath(path);
        task.setCreateTime(System.currentTimeMillis());
        task.setNextAttempt(0L);
        task.setAttempts(0);
        return insert(task).thenReturn(task);
    }

    /** 调用方已持项目、版本锁；按主键游标每批最多 100 条，全部任务与删除处于同一事务。 */
    public Mono<Void> enqueueVersionHistory(PrototypeVersionEntity version) {
        return Mono.just("")
                // 单分支 expand 只在整页登记完成后推进游标，不捕获已处理的实体页。
                .expand(after -> enqueueHistoryBatch(version, after), 1)
                .then()
                .then(
                        Mono.defer(
                                () ->
                                        insertTasks(
                                                List.of(
                                                        historyTask(
                                                                version,
                                                                version.getStoragePath())))));
    }

    private Mono<String> enqueueHistoryBatch(PrototypeVersionEntity version, String after) {
        return revisions
                .createQuery()
                .select(PrototypeRevisionEntity::getId, PrototypeRevisionEntity::getStoragePath)
                .where(PrototypeRevisionEntity::getVersionId, version.getId())
                .and(PrototypeRevisionEntity::getProjectId, version.getProjectId())
                .gt(PrototypeRevisionEntity::getId, after)
                .orderBy(SortOrder.asc(PrototypeRevisionEntity::getId))
                .paging(0, HISTORY_BATCH)
                .fetch()
                .collectList()
                .flatMap(
                        rows -> {
                            if (rows.isEmpty()) return Mono.empty();
                            // 先释放读取游标，再批量写入；pool=1 与单事务连接均不交叠读取和写入。
                            return insertTasks(
                                            rows.stream()
                                                    .map(
                                                            row ->
                                                                    historyTask(
                                                                            version,
                                                                            row.getStoragePath()))
                                                    .toList())
                                    .thenReturn(rows.getLast().getId());
                        });
    }

    private FileCleanupEntity historyTask(PrototypeVersionEntity version, String path) {
        FileCleanupEntity task = new FileCleanupEntity();
        task.setId(
                DigestUtils.md5DigestAsHex(
                        ("revision-delete:" + version.getId() + ":" + path)
                                .getBytes(StandardCharsets.UTF_8)));
        task.setResourceType("version");
        task.setProjectId(version.getProjectId());
        task.setVersionId(version.getId());
        task.setStoragePath(path);
        task.setCreateTime(System.currentTimeMillis());
        task.setNextAttempt(0L);
        task.setAttempts(0);
        return task;
    }

    // enqueueVersionHistory 的调用方持版本行锁；同版本的先查再插入在同一事务中串行，
    // 确定性任务 ID 使重试、分页和 current/history 重合的路径均不会重复登记。
    private Mono<Void> insertTasks(List<FileCleanupEntity> tasks) {
        List<FileCleanupEntity> unique =
                List.copyOf(
                        tasks.stream()
                                .collect(
                                        Collectors.toMap(
                                                FileCleanupEntity::getId,
                                                task -> task,
                                                (first, duplicate) -> first))
                                .values());
        Set<String> ids = unique.stream().map(FileCleanupEntity::getId).collect(Collectors.toSet());
        return createQuery()
                .select(FileCleanupEntity::getId)
                .in(FileCleanupEntity::getId, ids)
                .fetch()
                .map(FileCleanupEntity::getId)
                .collect(Collectors.toSet())
                .flatMap(
                        existing -> {
                            List<FileCleanupEntity> missing =
                                    unique.stream()
                                            .filter(task -> !existing.contains(task.getId()))
                                            .toList();
                            return missing.isEmpty()
                                    ? Mono.empty()
                                    : getRepository().insertBatch(missing).then();
                        });
    }

    private Mono<Boolean> referenced(FileCleanupEntity task) {
        if (task.getResourceType().equals("project")) {
            return projects.createQuery()
                    .select(PrototypeProjectEntity::getId)
                    .where(PrototypeProjectEntity::getId, task.getProjectId())
                    .paging(0, 1)
                    .fetchOne()
                    .hasElement()
                    .flatMap(
                            found ->
                                    found
                                            ? Mono.just(true)
                                            : versions.createQuery()
                                                    .select(PrototypeVersionEntity::getId)
                                                    .where(
                                                            PrototypeVersionEntity::getProjectId,
                                                            task.getProjectId())
                                                    .paging(0, 1)
                                                    .fetchOne()
                                                    .hasElement())
                    .flatMap(
                            found ->
                                    found
                                            ? Mono.just(true)
                                            : revisions
                                                    .createQuery()
                                                    .select(PrototypeRevisionEntity::getId)
                                                    .where(
                                                            PrototypeRevisionEntity::getProjectId,
                                                            task.getProjectId())
                                                    .paging(0, 1)
                                                    .fetchOne()
                                                    .hasElement());
        }
        return versions.createQuery()
                .select(PrototypeVersionEntity::getId)
                .where(PrototypeVersionEntity::getStoragePath, task.getStoragePath())
                .paging(0, 1)
                .fetchOne()
                .hasElement()
                .flatMap(
                        found ->
                                found
                                        ? Mono.just(true)
                                        : revisions
                                                .createQuery()
                                                .select(PrototypeRevisionEntity::getId)
                                                .where(
                                                        PrototypeRevisionEntity::getStoragePath,
                                                        task.getStoragePath())
                                                .paging(0, 1)
                                                .fetchOne()
                                                .hasElement());
    }

    public Mono<Void> attempt(FileCleanupEntity task) {
        // 新快照目录永久唯一、不复用；迁移只引用已由 p_version 保护的目录。
        // 引用存在时保留任务及文件并退避，避免历史数据或误入队任务被物理删除。
        Mono<Void> deletion =
                Mono.defer(
                        () -> {
                            if (!"project".equals(task.getResourceType())
                                    && !"version".equals(task.getResourceType()))
                                return Mono.error(
                                        new IllegalStateException("Unknown cleanup resource type"));
                            return referenced(task)
                                    .flatMap(
                                            live -> {
                                                if (live)
                                                    return Mono.error(
                                                            new IllegalStateException(
                                                                    "Referenced prototype"
                                                                            + " storage"));
                                                return task.getResourceType().equals("project")
                                                        ? storage.deleteProject(task.getProjectId())
                                                        : storage.deleteStoredVersion(
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

    @Scheduled(fixedDelayString = "${prototype-hub.cleanup.interval-ms:30000}")
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
