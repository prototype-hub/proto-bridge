package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.web.response.CapacityUsage;
import com.gaiys.proto.prototypehub.web.response.VersionUsageResponse;
import lombok.AllArgsConstructor;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 提供逻辑版本查询及创建容量校验。
 *
 * <p>由上传服务在项目行锁内调用容量校验；文件接收、替换及事务归属由上传服务协调。
 */
@Service
@AllArgsConstructor
public class PrototypeVersionService
        extends GenericReactiveCrudService<PrototypeVersionEntity, String> {
    private final PrototypeProjectService projects;

    public Mono<VersionUsageResponse> usage(String project) {
        return projects.requireById(project)
                .then(createQuery().where(PrototypeVersionEntity::getProjectId, project).count())
                .map(
                        used ->
                                new VersionUsageResponse(
                                        project, CapacityUsage.from(used.longValue())));
    }

    /** 调用方在项目行锁内检查版本行数；替换已有逻辑版本不经过此创建额度校验。 */
    public Mono<Void> requireCapacity(String project) {
        return createQuery()
                .where(PrototypeVersionEntity::getProjectId, project)
                .count()
                .flatMap(
                        used ->
                                used.longValue() < CapacityUsage.LIMIT
                                        ? Mono.empty()
                                        : Mono.error(
                                                new PrototypeHubException(
                                                        "error.prototype-hub.version_limit_reached",
                                                        "PROTOTYPE_HUB_VERSION_LIMIT_REACHED",
                                                        409,
                                                        CapacityUsage.LIMIT)));
    }

    public Flux<PrototypeVersionEntity> findAll(String project) {
        return project == null || project.isBlank()
                ? createQuery().fetch()
                : findByProjectId(project);
    }

    public Flux<PrototypeVersionEntity> findByProjectId(String project) {
        return createQuery().where(PrototypeVersionEntity::getProjectId, project).fetch();
    }

    public Mono<PrototypeVersionEntity> requireById(String id) {
        return findById(id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("version")));
    }

    public Mono<PrototypeVersionEntity> replace(
            PrototypeVersionEntity old,
            String name,
            String description,
            String path,
            String entry) {
        PrototypeVersionEntity update = old.copyTo(new PrototypeVersionEntity());
        update.setVersionName(name.trim());
        update.setDescription(description);
        update.setStoragePath(path);
        update.setEntryFile(entry);
        return createUpdate()
                .set(update)
                .where(PrototypeVersionEntity::getId, old.getId())
                .and(PrototypeVersionEntity::getStoragePath, old.getStoragePath())
                .execute()
                .flatMap(
                        rows ->
                                rows == 1
                                        ? Mono.just(update)
                                        : Mono.error(
                                                new PrototypeHubException(
                                                        "error.prototype-hub.version_conflict",
                                                        "PROTOTYPE_HUB_VERSION_CONFLICT",
                                                        409)));
    }
}
