package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.FileCleanupEntity;
import com.gaiys.proto.prototypehub.storage.paths.SafePaths;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@Slf4j
@AllArgsConstructor
public class FileCleanupService extends GenericReactiveCrudService<FileCleanupEntity, String> {
    private final PrototypeStorageService storage;

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

    public Mono<Void> attempt(FileCleanupEntity task) {
        Mono<Void> deletion =
                task.getResourceType().equals("project")
                        ? storage.deleteProject(task.getProjectId())
                        : storage.deleteStoredVersion(
                                task.getProjectId(), task.getStoragePath(), task.getVersionId());
        return deletion.then(deleteById(task.getId()))
                .then()
                .onErrorResume(
                        error -> {
                            log.warn(
                                    "File cleanup pending: task={}, project={}, result={}, errorType={}",
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
                                                        "Unable to update cleanup retry state: task={}",
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
                                .fetch()
                                .take(50)
                                .concatMap(this::attempt)
                                .then());
    }
}
