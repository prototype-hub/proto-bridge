package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.builder.PrototypeManifestBuilder;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.response.RevisionMigrationResponse;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 仅由管理员显式执行的单逻辑版本迁移。EasyORM 只补结构，启动时绝不猜测旧历史。 文件散列在事务外完成；项目→版本锁内重验投影，然后原子发布快照及旧分享/回执绑定。
 * 已经被旧系统覆盖并删除的内容无法恢复，旧绑定只固定迁移时仍可用的内容。
 */
@Service
@RequiredArgsConstructor
public class PrototypeRevisionMigrationService {
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeStorageService storage;
    private final PrototypeManifestBuilder manifests;
    private final FileWork work;
    private final DatabaseClient database;
    private final TransactionalOperator transactions;

    public Mono<RevisionMigrationResponse> migrate(String versionId, boolean apply) {
        return access.requireAdministrator()
                .then(access.requireVersion(versionId, PrototypeProjectAccessService.Action.EDIT))
                .flatMap(
                        before ->
                                prepare(before)
                                        .flatMap(
                                                revision ->
                                                        apply
                                                                ? apply(before, revision)
                                                                : counts(before)
                                                                        .map(
                                                                                count ->
                                                                                        response(
                                                                                                before,
                                                                                                revision,
                                                                                                count,
                                                                                                false))));
    }

    private Mono<PrototypeRevisionEntity> prepare(PrototypeVersionEntity before) {
        if (before.getCurrentRevisionId() != null) return revisions.current(before);
        return work.call(() -> manifests.build(storage.site(before), before.getEntryFile()))
                .map(
                        manifest ->
                                revisions.candidate(
                                        before, PrototypeShareService.random(), manifest))
                .onErrorMap(
                        error -> !(error instanceof PrototypeHubException),
                        error ->
                                new PrototypeHubException(
                                        "error.prototype-hub.revision_migration_invalid",
                                        "PROTOTYPE_HUB_REVISION_MIGRATION_INVALID",
                                        409));
    }

    private Mono<RevisionMigrationResponse> apply(
            PrototypeVersionEntity before, PrototypeRevisionEntity revision) {
        return access.lockProject(before.getProjectId())
                .then(
                        database.sql("select id from p_version where id=:id for update")
                                .bind("id", before.getId())
                                .fetch()
                                .one()
                                .switchIfEmpty(
                                        Mono.error(() -> PrototypeHubException.missing("version"))))
                .then(access.requireAdministrator())
                .then(
                        access.requireVersion(
                                before.getId(), PrototypeProjectAccessService.Action.EDIT))
                .flatMap(
                        current -> {
                            if (!Objects.equals(
                                            current.getCurrentRevisionId(),
                                            before.getCurrentRevisionId())
                                    || !Objects.equals(
                                            current.getStoragePath(), before.getStoragePath())
                                    || !Objects.equals(
                                            current.getEntryFile(), before.getEntryFile()))
                                return Mono.error(
                                        new PrototypeHubException(
                                                "error.prototype-hub.version_conflict",
                                                "PROTOTYPE_HUB_VERSION_CONFLICT",
                                                409));
                            return counts(current)
                                    .flatMap(
                                            count ->
                                                    publish(current, revision)
                                                            .then(
                                                                    bindLegacy(
                                                                            current,
                                                                            revision.getId()))
                                                            .thenReturn(
                                                                    response(
                                                                            current, revision,
                                                                            count, true)));
                        })
                .as(transactions::transactional);
    }

    private Mono<Void> publish(PrototypeVersionEntity version, PrototypeRevisionEntity revision) {
        if (version.getCurrentRevisionId() != null) return Mono.empty();
        return revisions
                .insert(revision)
                .then(
                        database.sql(
                                        "update p_version set current_revision_id=:revision where"
                                                + " id=:id and current_revision_id is null and"
                                                + " storage_path=:path and entry_file=:entry")
                                .bind("revision", revision.getId())
                                .bind("id", version.getId())
                                .bind("path", version.getStoragePath())
                                .bind("entry", version.getEntryFile())
                                .fetch()
                                .rowsUpdated())
                .flatMap(
                        rows ->
                                rows == 1
                                        ? Mono.empty()
                                        : Mono.error(
                                                new PrototypeHubException(
                                                        "error.prototype-hub.version_conflict",
                                                        "PROTOTYPE_HUB_VERSION_CONFLICT",
                                                        409)));
    }

    private Mono<Void> bindLegacy(PrototypeVersionEntity version, String revisionId) {
        return database.sql(
                        "update p_share set revision_id=:revision where project_id=:project and"
                                + " version_id=:version and revision_id is null")
                .bind("revision", revisionId)
                .bind("project", version.getProjectId())
                .bind("version", version.getId())
                .fetch()
                .rowsUpdated()
                .then(
                        database.sql(
                                        "update p_creation_receipt set revision_id=:revision where"
                                                + " resource_id=:version and revision_id is null")
                                .bind("revision", revisionId)
                                .bind("version", version.getId())
                                .fetch()
                                .rowsUpdated())
                .then();
    }

    private Mono<Counts> counts(PrototypeVersionEntity version) {
        // 两个标量子查询一次返回，不让未消费的行游标占用迁移事务连接。
        return database.sql(
                        "select (select count(*) from p_share where project_id=:project and"
                                + " version_id=:version and revision_id is null) as shares, (select"
                                + " count(*) from p_creation_receipt where resource_id=:version and"
                                + " revision_id is null) as receipts")
                .bind("project", version.getProjectId())
                .bind("version", version.getId())
                .map(
                        (row, metadata) ->
                                new Counts(
                                        row.get("shares", Long.class),
                                        row.get("receipts", Long.class)))
                .one();
    }

    private RevisionMigrationResponse response(
            PrototypeVersionEntity version,
            PrototypeRevisionEntity revision,
            Counts count,
            boolean applied) {
        return new RevisionMigrationResponse(
                version.getId(),
                !applied
                        && (version.getCurrentRevisionId() == null
                                || count.shares() > 0
                                || count.receipts() > 0),
                revision.getId(),
                revision.getManifestSha256(),
                revision.getFileCount(),
                revision.getTotalBytes(),
                count.shares(),
                count.receipts(),
                applied);
    }

    private record Counts(long shares, long receipts) {}
}
