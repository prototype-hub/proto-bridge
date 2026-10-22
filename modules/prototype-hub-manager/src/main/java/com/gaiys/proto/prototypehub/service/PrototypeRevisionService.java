package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.builder.PrototypeManifestBuilder;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import io.r2dbc.spi.Row;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 不可变快照的关联校验及有界历史读取；通用动作和项目范围由调用入口先检查。 */
@Service
@RequiredArgsConstructor
public class PrototypeRevisionService
        extends GenericReactiveCrudService<PrototypeRevisionEntity, String> {
    private static final String METADATA_COLUMNS =
            "id,project_id,version_id,version_name,description,storage_path,entry_file,manifest_sha256,file_count,total_bytes,creator_id,create_time";
    private final DatabaseClient database;
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
        return database.sql(
                        "select "
                                + METADATA_COLUMNS
                                + " from p_revision where id=:id and version_id=:version and"
                                + " project_id=:project")
                .bind("id", revisionId)
                .bind("version", version.getId())
                .bind("project", version.getProjectId())
                .map((row, metadata) -> metadata(row))
                .one()
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
                        : database.sql(
                                        "select id,create_time from p_revision where id=:id and"
                                                + " version_id=:version and project_id=:project")
                                .bind("id", after)
                                .bind("version", version.getId())
                                .bind("project", version.getProjectId())
                                .map(
                                        (row, metadata) -> {
                                            PrototypeRevisionEntity r =
                                                    new PrototypeRevisionEntity();
                                            r.setId(row.get("id", String.class));
                                            r.setCreateTime(row.get("create_time", Long.class));
                                            return r;
                                        })
                                .one()
                                .switchIfEmpty(
                                        Mono.error(
                                                () -> PrototypeHubException.missing("revision")));
        return cursor.flatMapMany(
                c -> {
                    var query =
                            database.sql(
                                            "select "
                                                    + METADATA_COLUMNS
                                                    + " from p_revision where version_id=:versionId"
                                                    + " and project_id=:projectId"
                                                    + (c.getId() == null
                                                            ? ""
                                                            : " and (create_time<:time or"
                                                                    + " (create_time=:time and"
                                                                    + " id<:after))")
                                                    + " order by create_time desc,id desc limit"
                                                    + " :limit")
                                    .bind("versionId", version.getId())
                                    .bind("projectId", version.getProjectId())
                                    .bind("limit", limit);
                    if (c.getId() != null)
                        query = query.bind("time", c.getCreateTime()).bind("after", c.getId());
                    return query.map((row, metadata) -> metadata(row)).all();
                });
    }

    /** 只有页面清单入口按需读取 manifest 大字段，资源链路只取不可变元数据。 */
    public Mono<List<PrototypePageInfo>> pages(PrototypeRevisionEntity revision) {
        return database.sql(
                        "select manifest_json from p_revision where id=:id and version_id=:version"
                                + " and project_id=:project")
                .bind("id", revision.getId())
                .bind("version", revision.getVersionId())
                .bind("project", revision.getProjectId())
                .map((row, metadata) -> row.get("manifest_json", String.class))
                .one()
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("revision")))
                // 完整释放数据库读取后，在现有专用调度器解析大清单，不占 R2DBC 事件线程。
                .flatMap(json -> work.call(() -> PrototypeManifestBuilder.parse(json).pages()));
    }

    private static PrototypeRevisionEntity metadata(Row row) {
        PrototypeRevisionEntity r = new PrototypeRevisionEntity();
        r.setId(row.get("id", String.class));
        r.setProjectId(row.get("project_id", String.class));
        r.setVersionId(row.get("version_id", String.class));
        r.setVersionName(row.get("version_name", String.class));
        r.setDescription(row.get("description", String.class));
        r.setStoragePath(row.get("storage_path", String.class));
        r.setEntryFile(row.get("entry_file", String.class));
        r.setManifestSha256(row.get("manifest_sha256", String.class));
        r.setFileCount(row.get("file_count", Integer.class));
        r.setTotalBytes(row.get("total_bytes", Long.class));
        r.setCreatorId(row.get("creator_id", String.class));
        r.setCreateTime(row.get("create_time", Long.class));
        return r;
    }

    public static PrototypeHubException migrationRequired() {
        return new PrototypeHubException(
                "error.prototype-hub.revision_migration_required",
                "PROTOTYPE_HUB_REVISION_MIGRATION_REQUIRED",
                409);
    }
}
