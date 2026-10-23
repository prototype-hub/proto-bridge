package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.web.response.CapacityUsage;
import com.gaiys.proto.prototypehub.web.response.VersionResponse;
import com.gaiys.proto.prototypehub.web.response.VersionUsageResponse;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.Selects;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.crud.query.QueryHelper;
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
    private final ReactiveRepository<PrototypeRevisionEntity, String> revisions;

    public Mono<VersionResponse> response(PrototypeVersionEntity version) {
        return revisions
                .createQuery()
                .where(PrototypeRevisionEntity::getVersionId, version.getId())
                .count()
                .map(count -> VersionResponse.from(version, count.longValue()));
    }

    /** 仅对已鉴权的有界页面批量统计修订，保留框架分页元信息，不逐条 COUNT 或读取清单正文。 */
    public Mono<PagerResult<VersionResponse>> responses(
            Mono<PagerResult<PrototypeVersionEntity>> page) {
        return QueryHelper.transformPageResult(page, this::responses);
    }

    private Mono<List<VersionResponse>> responses(List<PrototypeVersionEntity> page) {
        if (page.isEmpty()) return Mono.just(List.of());
        List<String> ids = page.stream().map(PrototypeVersionEntity::getId).toList();
        return revisionCounts(ids)
                .map(
                        counts ->
                                page.stream()
                                        .map(
                                                version ->
                                                        VersionResponse.from(
                                                                version,
                                                                counts.getOrDefault(
                                                                        version.getId(), 0L)))
                                        .toList());
    }

    private Mono<Map<String, Long>> revisionCounts(List<String> ids) {
        // nativeQuery 是官方结构化 QueryOperator，不接收或构造原生 SQL 字符串。
        return revisions
                .nativeQuery()
                .select(
                        Selects.column("versionId").as("version_id"),
                        Selects.count1().as("revision_count"))
                .where(condition -> condition.in(PrototypeRevisionEntity::getVersionId, ids))
                .groupBy(Selects.column("versionId").get())
                .fetch(ResultWrappers.map())
                .reactive()
                .collectMap(
                        row -> (String) row.get("version_id"),
                        row -> ((Number) row.get("revision_count")).longValue());
    }

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
            String entry,
            String revisionId) {
        PrototypeVersionEntity update = old.copyTo(new PrototypeVersionEntity());
        update.setVersionName(name.trim());
        update.setDescription(description);
        update.setStoragePath(path);
        update.setEntryFile(entry);
        update.setCurrentRevisionId(revisionId);
        return createUpdate()
                .set(update)
                .where(PrototypeVersionEntity::getId, old.getId())
                .and(PrototypeVersionEntity::getStoragePath, old.getStoragePath())
                .and(PrototypeVersionEntity::getCurrentRevisionId, old.getCurrentRevisionId())
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
