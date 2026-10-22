package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.FileCleanupEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
@AllArgsConstructor
public class FileCleanupService extends GenericReactiveCrudService<FileCleanupEntity, String> {
    private final PrototypeStorageService storage;
    private final DatabaseClient database;

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

    /** 调用方已持项目、版本锁；所有历史及兼容投影目录在同一删除事务中登记。 */
    public Mono<Void> enqueueVersionHistory(PrototypeVersionEntity version) {
        return database.sql(
                        """
insert into p_file_cleanup(id,resource_type,project_id,version_id,storage_path,create_time,next_attempt,attempts)
select md5('revision-delete:' || :version || ':' || paths.storage_path), 'version', :project, :version, paths.storage_path, :now, 0, 0
from (select storage_path from p_revision where version_id=:version and project_id=:project
      union select storage_path from p_version where id=:version and project_id=:project) paths
on conflict(id) do nothing
""")
                .bind("version", version.getId())
                .bind("project", version.getProjectId())
                .bind("now", System.currentTimeMillis())
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Mono<Boolean> referenced(FileCleanupEntity task) {
        String sql =
                task.getResourceType().equals("project")
                        ? "select exists(select 1 from p_project where id=:project) or"
                                + " exists(select 1 from p_version where project_id=:project) or"
                                + " exists(select 1 from p_revision where project_id=:project) as"
                                + " live"
                        : "select exists(select 1 from p_version where storage_path=:path) or"
                                + " exists(select 1 from p_revision where storage_path=:path) as"
                                + " live";
        var query = database.sql(sql);
        query =
                task.getResourceType().equals("project")
                        ? query.bind("project", task.getProjectId())
                        : query.bind("path", task.getStoragePath());
        return query.map((row, metadata) -> Boolean.TRUE.equals(row.get("live", Boolean.class)))
                .one();
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
                        database.sql(
                                        "select"
                                                + " id,resource_type,project_id,version_id,storage_path,create_time,next_attempt,attempts"
                                                + " from p_file_cleanup where next_attempt<=:now order"
                                                + " by next_attempt,id limit 50")
                                .bind("now", System.currentTimeMillis())
                                .map(
                                        (row, metadata) -> {
                                            FileCleanupEntity task = new FileCleanupEntity();
                                            task.setId(row.get("id", String.class));
                                            task.setResourceType(
                                                    row.get("resource_type", String.class));
                                            task.setProjectId(row.get("project_id", String.class));
                                            task.setVersionId(row.get("version_id", String.class));
                                            task.setStoragePath(
                                                    row.get("storage_path", String.class));
                                            task.setCreateTime(row.get("create_time", Long.class));
                                            task.setNextAttempt(
                                                    row.get("next_attempt", Long.class));
                                            task.setAttempts(row.get("attempts", Integer.class));
                                            return task;
                                        })
                                .all()
                                .collectList()
                                // 先消费并释放查询连接，再逐任务 I/O/更新；pool=1 也不会在游标内等待写连接。
                                .flatMapMany(Flux::fromIterable)
                                .concatMap(this::attempt, 1)
                                .then());
    }
}
