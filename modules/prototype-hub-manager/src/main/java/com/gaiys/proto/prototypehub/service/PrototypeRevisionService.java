package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.builder.PrototypeManifestBuilder;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 不可变快照的关联校验及有界历史读取；通用动作和项目范围由调用入口先检查。 */
@Service
@RequiredArgsConstructor
public class PrototypeRevisionService
        extends GenericReactiveCrudService<PrototypeRevisionEntity, String> {
    private final FileWork work;

    public PrototypeRevisionEntity candidate(
            PrototypeVersionEntity version,
            String revisionId,
            PrototypeManifestBuilder.Built manifest) {
        PrototypeRevisionEntity revision = new PrototypeRevisionEntity();
        revision.setId(revisionId);
        revision.setProjectId(version.getProjectId());
        revision.setVersionId(version.getId());
        revision.setVersionName(version.getVersionName());
        revision.setDescription(version.getDescription());
        revision.setStoragePath(version.getStoragePath());
        revision.setEntryFile(version.getEntryFile());
        revision.setManifestJson(manifest.json());
        revision.setManifestSha256(manifest.sha256());
        revision.setFileCount(manifest.fileCount());
        revision.setTotalBytes(manifest.totalBytes());
        revision.setCreatorId(version.getCreatorId());
        revision.setCreateTime(System.currentTimeMillis());
        return revision;
    }

    public Mono<PrototypeRevisionEntity> current(PrototypeVersionEntity version) {
        if (version.getCurrentRevisionId() == null) return Mono.error(migrationRequired());
        return require(version, version.getCurrentRevisionId())
                .filter(
                        r ->
                                Objects.equals(r.getStoragePath(), version.getStoragePath())
                                        && Objects.equals(r.getEntryFile(), version.getEntryFile()))
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("revision")));
    }

    public Mono<PrototypeRevisionEntity> require(
            PrototypeVersionEntity version, String revisionId) {
        if (version.getCurrentRevisionId() == null || revisionId == null)
            return Mono.error(migrationRequired());
        // 所有资源鉴权、分享信息和元数据请求均不传输大清单；撤权仍逐请求查库，不缓存权限。
        return createQuery()
                .selectExcludes(PrototypeRevisionEntity::getManifestJson)
                .where(PrototypeRevisionEntity::getId, revisionId)
                .and(PrototypeRevisionEntity::getVersionId, version.getId())
                .and(PrototypeRevisionEntity::getProjectId, version.getProjectId())
                .fetchOne()
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("revision")));
    }

    public Flux<PrototypeRevisionEntity> history(
            PrototypeVersionEntity version, String after, int limit) {
        if (limit < 1 || limit > 100)
            return Flux.error(
                    PrototypeHubException.badRequest(
                            "error.prototype-hub.revision_page_invalid",
                            "PROTOTYPE_HUB_REVISION_PAGE_INVALID"));
        if (version.getCurrentRevisionId() == null) return Flux.error(migrationRequired());
        Mono<PrototypeRevisionEntity> cursor =
                after == null || after.isBlank()
                        ? Mono.just(new PrototypeRevisionEntity())
                        : createQuery()
                                .select(
                                        PrototypeRevisionEntity::getId,
                                        PrototypeRevisionEntity::getCreateTime)
                                .where(PrototypeRevisionEntity::getId, after)
                                .and(PrototypeRevisionEntity::getVersionId, version.getId())
                                .and(PrototypeRevisionEntity::getProjectId, version.getProjectId())
                                .fetchOne()
                                .switchIfEmpty(
                                        Mono.error(
                                                () -> PrototypeHubException.missing("revision")));
        return cursor.flatMapMany(
                c -> {
                    var query =
                            createQuery()
                                    .selectExcludes(PrototypeRevisionEntity::getManifestJson)
                                    .where(PrototypeRevisionEntity::getVersionId, version.getId())
                                    .and(
                                            PrototypeRevisionEntity::getProjectId,
                                            version.getProjectId());
                    if (c.getId() != null) {
                        query.nest()
                                .lt(PrototypeRevisionEntity::getCreateTime, c.getCreateTime())
                                .orNest()
                                .is(PrototypeRevisionEntity::getCreateTime, c.getCreateTime())
                                .lt(PrototypeRevisionEntity::getId, c.getId())
                                .end()
                                .end();
                    }
                    return query.orderBy(
                                    SortOrder.desc(PrototypeRevisionEntity::getCreateTime),
                                    SortOrder.desc(PrototypeRevisionEntity::getId))
                            .paging(0, limit)
                            .fetch();
                });
    }

    /** 只有页面清单入口按需读取 manifest 大字段，资源链路只取不可变元数据。 */
    public Mono<List<PrototypePageInfo>> pages(PrototypeRevisionEntity revision) {
        return createQuery()
                .select(PrototypeRevisionEntity::getManifestJson)
                .where(PrototypeRevisionEntity::getId, revision.getId())
                .and(PrototypeRevisionEntity::getVersionId, revision.getVersionId())
                .and(PrototypeRevisionEntity::getProjectId, revision.getProjectId())
                .fetchOne()
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("revision")))
                .map(PrototypeRevisionEntity::getManifestJson)
                // 完整释放数据库读取后，在现有专用调度器解析大清单，不占 R2DBC 事件线程。
                .flatMap(json -> work.call(() -> PrototypeManifestBuilder.parse(json).pages()));
    }

    public static PrototypeHubException migrationRequired() {
        return new PrototypeHubException(
                "error.prototype-hub.revision_migration_required",
                "PROTOTYPE_HUB_REVISION_MIGRATION_REQUIRED",
                409);
    }
}
