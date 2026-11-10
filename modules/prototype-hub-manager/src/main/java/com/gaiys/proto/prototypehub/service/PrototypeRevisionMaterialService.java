package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeCreationReceiptService.Request;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.validation.RevisionMaterialSource;
import com.gaiys.proto.prototypehub.web.request.RevisionMaterialRequests;
import com.gaiys.proto.prototypehub.web.response.RevisionMaterialResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 固定修订资料的唯一持久化边界；来源由成员声明，原始载荷保持不变。 */
@Service
@RequiredArgsConstructor
public class PrototypeRevisionMaterialService {
    public static final int MAX_FILES = 100;
    public static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;
    private final ReactiveRepository<PrototypeRevisionMaterialEntity, String> materials;
    private final PrototypeRevisionMaterialFileService files;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeCreationReceiptService receipts;
    private final FileCleanupService cleanup;
    private final TransactionalOperator transactions;

    private record Scope(
            String user, String project, String version, PrototypeRevisionEntity revision) {}

    public Mono<PagerResult<RevisionMaterialResponse>> query(
            String version, String revision, RevisionMaterialRequests.Query input) {
        return prepare(version, revision, Action.READ)
                .flatMap(
                        scope -> {
                            var builder =
                                    QueryParamEntity.newQuery()
                                            .where(
                                                    PrototypeRevisionMaterialEntity::getProjectId,
                                                    scope.project())
                                            .and(
                                                    PrototypeRevisionMaterialEntity::getVersionId,
                                                    scope.version())
                                            .and(
                                                    PrototypeRevisionMaterialEntity::getRevisionId,
                                                    scope.revision().getId());
                            if (input.getMaterialType() != null)
                                builder.and(
                                        PrototypeRevisionMaterialEntity::getMaterialType,
                                        input.getMaterialType());
                            QueryParamEntity query =
                                    builder.orderByDesc(
                                                    PrototypeRevisionMaterialEntity::getCreatedAt)
                                            .orderByAsc(PrototypeRevisionMaterialEntity::getId)
                                            .getParam();
                            query.doPaging(input.getPageIndex(), input.getPageSize());
                            query.setParallelPager(true);
                            return QueryHelper.queryPager(
                                    query, materials::createQuery, RevisionMaterialResponse::of);
                        })
                .checkpoint("prototype-hub/material/query");
    }

    public Mono<RevisionMaterialResponse> upload(
            String version,
            String revision,
            FilePart file,
            RevisionMaterialRequests.Source input,
            String key) {
        return Mono.defer(
                        () -> {
                            var source = RevisionMaterialSource.of(input);
                            return prepare(version, revision, Action.EDIT)
                                    .flatMap(
                                            scope ->
                                                    files.upload(
                                                            scope.project(),
                                                            version,
                                                            file,
                                                            candidate ->
                                                                    locked(
                                                                            scope,
                                                                            () ->
                                                                                    create(
                                                                                            scope,
                                                                                            candidate,
                                                                                            source,
                                                                                            key))));
                        })
                .checkpoint("prototype-hub/material/upload");
    }

    private Mono<PrototypeRevisionMaterialFileService.Stored<RevisionMaterialResponse>> create(
            Scope scope,
            PrototypeRevisionMaterialFileService.Candidate candidate,
            RevisionMaterialSource source,
            String key) {
        Request request =
                request(
                        scope,
                        "create",
                        key,
                        null,
                        null,
                        source,
                        candidate.fileName(),
                        candidate.type(),
                        candidate.mediaType(),
                        candidate.sha256());
        String id = request == null ? PrototypeShareService.random() : request.id();
        return receipts.replay(request, ignored -> find(scope, id))
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    var row = row(scope, id, candidate, source);
                                    return requireCapacity(scope, candidate.size())
                                            .then(materials.insert(row))
                                            .then(record(scope, request))
                                            .thenReturn(row);
                                }))
                .map(
                        row ->
                                new PrototypeRevisionMaterialFileService.Stored<>(
                                        RevisionMaterialResponse.of(row), row.getStoragePath()));
    }

    private Mono<Void> requireCapacity(Scope scope, long incoming) {
        return materials
                .createQuery()
                .select(PrototypeRevisionMaterialEntity::getFileSize)
                .where(PrototypeRevisionMaterialEntity::getRevisionId, scope.revision().getId())
                .not(PrototypeRevisionMaterialEntity::getMaterialType, "prototype")
                .paging(0, MAX_FILES + 1)
                .fetch()
                .collectList()
                .flatMap(
                        rows -> {
                            long total = incoming;
                            for (var row : rows) total += row.getFileSize();
                            return rows.size() >= MAX_FILES || total > MAX_TOTAL_BYTES
                                    ? Mono.error(
                                            new PrototypeHubException(
                                                    "error.prototype-hub.material_limit_reached",
                                                    "PROTOTYPE_HUB_MATERIAL_LIMIT_REACHED",
                                                    409))
                                    : Mono.empty();
                        });
    }

    public Mono<RevisionMaterialResponse> createSource(
            String version, String revision, RevisionMaterialRequests.CreateSource input) {
        return Mono.defer(
                        () -> {
                            var source = RevisionMaterialSource.of(input);
                            if (!source.recorded())
                                return Mono.error(RevisionMaterialSource.invalid());
                            return prepare(version, revision, Action.EDIT)
                                    .flatMap(
                                            scope ->
                                                    createSource(
                                                            scope,
                                                            input.getClientRequestId(),
                                                            source));
                        })
                .checkpoint("prototype-hub/material/create-source");
    }

    private Mono<RevisionMaterialResponse> createSource(
            Scope scope, String key, RevisionMaterialSource source) {
        return files.prototype(scope.revision())
                .flatMap(
                        candidate ->
                                locked(
                                        scope,
                                        () -> createDeclaration(scope, candidate, source, key)));
    }

    private Mono<RevisionMaterialResponse> createDeclaration(
            Scope scope,
            PrototypeRevisionMaterialFileService.Candidate candidate,
            RevisionMaterialSource source,
            String key) {
        Request request =
                request(scope, "prototype-source", key, scope.revision().getId(), null, source);
        return receipts.replay(request, ignored -> find(scope, request.id()))
                .switchIfEmpty(
                        Mono.defer(() -> insertDeclaration(scope, candidate, source, request)))
                .map(RevisionMaterialResponse::of);
    }

    private Mono<PrototypeRevisionMaterialEntity> insertDeclaration(
            Scope scope,
            PrototypeRevisionMaterialFileService.Candidate candidate,
            RevisionMaterialSource source,
            Request request) {
        return materials
                .createQuery()
                .where(PrototypeRevisionMaterialEntity::getRevisionId, scope.revision().getId())
                .and(PrototypeRevisionMaterialEntity::getMaterialType, "prototype")
                .count()
                .flatMap(
                        count -> {
                            if (count > 0) return Mono.error(conflict());
                            var row = row(scope, request.id(), candidate, source);
                            return materials
                                    .insert(row)
                                    .then(record(scope, request))
                                    .thenReturn(row);
                        });
    }

    /** 调用者在文件线程完成摘要后，再将声明和不可变修订一起提交。 */
    public Mono<Optional<PrototypeRevisionMaterialEntity>> preparePrototype(
            PrototypeRevisionEntity revision, RevisionMaterialSource source) {
        if (!source.recorded()) return Mono.just(Optional.empty());
        Scope scope =
                new Scope(
                        revision.getCreatorId(),
                        revision.getProjectId(),
                        revision.getVersionId(),
                        revision);
        return files.prototype(revision)
                .map(
                        candidate ->
                                Optional.of(
                                        row(
                                                scope,
                                                PrototypeShareService.random(),
                                                candidate,
                                                source)));
    }

    public Mono<Void> insertPrototype(Optional<PrototypeRevisionMaterialEntity> candidate) {
        return candidate.map(row -> materials.insert(row).then()).orElseGet(Mono::empty);
    }

    public Mono<RevisionMaterialResponse> update(
            String version, String revision, String id, RevisionMaterialRequests.Update input) {
        return Mono.defer(
                        () -> {
                            var source = RevisionMaterialSource.of(input);
                            return prepare(version, revision, Action.EDIT)
                                    .flatMap(
                                            scope ->
                                                    locked(
                                                            scope,
                                                            () -> {
                                                                Request request =
                                                                        request(
                                                                                scope,
                                                                                "update",
                                                                                input
                                                                                        .getClientRequestId(),
                                                                                id,
                                                                                input
                                                                                        .getExpectedVersion(),
                                                                                source);
                                                                return receipts.replay(
                                                                                request,
                                                                                ignored ->
                                                                                        find(
                                                                                                scope,
                                                                                                id))
                                                                        .switchIfEmpty(
                                                                                Mono.defer(
                                                                                        () ->
                                                                                                require(
                                                                                                                scope,
                                                                                                                id)
                                                                                                        .flatMap(
                                                                                                                row ->
                                                                                                                        update(
                                                                                                                                scope,
                                                                                                                                row,
                                                                                                                                input,
                                                                                                                                source,
                                                                                                                                request))))
                                                                        .map(
                                                                                RevisionMaterialResponse
                                                                                        ::of);
                                                            }));
                        })
                .checkpoint("prototype-hub/material/update");
    }

    private Mono<PrototypeRevisionMaterialEntity> update(
            Scope scope,
            PrototypeRevisionMaterialEntity row,
            RevisionMaterialRequests.Update input,
            RevisionMaterialSource source,
            Request request) {
        checkVersion(row, input.getExpectedVersion());
        source.apply(row);
        row.setConfigVersion(row.getConfigVersion() + 1);
        row.setUpdatedAt(System.currentTimeMillis());
        row.setUpdatedBy(scope.user());
        var update =
                materials
                        .createUpdate()
                        .set(row::getSourceTool)
                        .set(row::getSourceName)
                        .set(row::getSourceUrl)
                        .set(row::getSourceNotes)
                        .set(row::getConfigVersion)
                        .set(row::getUpdatedAt)
                        .set(row::getUpdatedBy);
        if (row.getSourceTool() == null)
            update.setNull(PrototypeRevisionMaterialEntity::getSourceTool);
        if (row.getSourceName() == null)
            update.setNull(PrototypeRevisionMaterialEntity::getSourceName);
        if (row.getSourceUrl() == null)
            update.setNull(PrototypeRevisionMaterialEntity::getSourceUrl);
        if (row.getSourceNotes() == null)
            update.setNull(PrototypeRevisionMaterialEntity::getSourceNotes);
        return update.where(PrototypeRevisionMaterialEntity::getId, row.getId())
                .and(PrototypeRevisionMaterialEntity::getConfigVersion, input.getExpectedVersion())
                .execute()
                .flatMap(changed -> changed == 1 ? record(scope, request) : Mono.error(conflict()))
                .thenReturn(row);
    }

    public Mono<Boolean> delete(
            String version, String revision, String id, RevisionMaterialRequests.Delete input) {
        return prepare(version, revision, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        () -> {
                                            Request request =
                                                    request(
                                                            scope,
                                                            "delete",
                                                            input.getClientRequestId(),
                                                            id,
                                                            input.getExpectedVersion(),
                                                            null);
                                            return receipts.replay(
                                                            request, ignored -> Mono.just(true))
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () ->
                                                                            require(scope, id)
                                                                                    .flatMap(
                                                                                            row -> {
                                                                                                checkVersion(
                                                                                                        row,
                                                                                                        input
                                                                                                                .getExpectedVersion());
                                                                                                return queue(
                                                                                                                row)
                                                                                                        .then(
                                                                                                                materials
                                                                                                                        .createDelete()
                                                                                                                        .where(
                                                                                                                                PrototypeRevisionMaterialEntity
                                                                                                                                        ::getId,
                                                                                                                                id)
                                                                                                                        .and(
                                                                                                                                PrototypeRevisionMaterialEntity
                                                                                                                                        ::getConfigVersion,
                                                                                                                                input
                                                                                                                                        .getExpectedVersion())
                                                                                                                        .execute())
                                                                                                        .flatMap(
                                                                                                                changed ->
                                                                                                                        changed
                                                                                                                                        == 1
                                                                                                                                ? record(
                                                                                                                                                scope,
                                                                                                                                                request)
                                                                                                                                        .thenReturn(
                                                                                                                                                true)
                                                                                                                                : Mono
                                                                                                                                        .error(
                                                                                                                                                conflict()));
                                                                                            })));
                                        }))
                .checkpoint("prototype-hub/material/delete");
    }

    public Mono<ResponseEntity<Resource>> read(String version, String revision, String id) {
        return prepare(version, revision, Action.READ)
                .flatMap(scope -> require(scope, id))
                .flatMap(files::read);
    }

    private Mono<Scope> prepare(String version, String revision, Action action) {
        return access.current()
                .flatMap(
                        actor ->
                                authorize(actor.userId(), version, action, true)
                                        .flatMap(
                                                row ->
                                                        revisions
                                                                .require(row, revision)
                                                                .map(
                                                                        fixed ->
                                                                                new Scope(
                                                                                        actor
                                                                                                .userId(),
                                                                                        row
                                                                                                .getProjectId(),
                                                                                        version,
                                                                                        fixed))));
    }

    private Mono<PrototypeVersionEntity> authorize(
            String user, String version, Action action, boolean audit) {
        return access.actor(user)
                .flatMap(
                        actor ->
                                actor.authentication()
                                                .hasPermission(
                                                        "prototype-hub-version",
                                                        action == Action.READ ? "query" : "save")
                                        ? access.requireVersion(actor, version, action, audit)
                                        : Mono.error(PrototypeProjectAccessService.roleDenied()));
    }

    private <T> Mono<T> locked(Scope scope, Supplier<Mono<T>> operation) {
        return access.lockActiveUsers(scope.user())
                .then(access.lockProject(scope.project()))
                .then(access.lockVersion(scope.version()))
                .then(authorize(scope.user(), scope.version(), Action.EDIT, false))
                .filter(version -> scope.project().equals(version.getProjectId()))
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("version")))
                .flatMap(version -> revisions.require(version, scope.revision().getId()))
                .then(Mono.defer(operation))
                .as(transactions::transactional);
    }

    private Mono<PrototypeRevisionMaterialEntity> find(Scope scope, String id) {
        return materials
                .createQuery()
                .where(PrototypeRevisionMaterialEntity::getId, id)
                .and(PrototypeRevisionMaterialEntity::getProjectId, scope.project())
                .and(PrototypeRevisionMaterialEntity::getVersionId, scope.version())
                .and(PrototypeRevisionMaterialEntity::getRevisionId, scope.revision().getId())
                .fetch()
                .singleOrEmpty();
    }

    private Mono<PrototypeRevisionMaterialEntity> require(Scope scope, String id) {
        return find(scope, id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("material")));
    }

    private PrototypeRevisionMaterialEntity row(
            Scope scope,
            String id,
            PrototypeRevisionMaterialFileService.Candidate candidate,
            RevisionMaterialSource source) {
        var row = new PrototypeRevisionMaterialEntity();
        row.setId(id);
        row.setProjectId(scope.project());
        row.setVersionId(scope.version());
        row.setRevisionId(scope.revision().getId());
        candidate.apply(row);
        source.apply(row);
        row.setConfigVersion(0L);
        row.setCreatedBy(scope.user());
        row.setUpdatedBy(scope.user());
        row.setCreatedAt(System.currentTimeMillis());
        row.setUpdatedAt(row.getCreatedAt());
        return row;
    }

    private Request request(
            Scope scope,
            String action,
            String key,
            String id,
            Long expected,
            RevisionMaterialSource source,
            String... payload) {
        var fields =
                new ArrayList<String>(
                        Arrays.asList(
                                scope.project(),
                                scope.version(),
                                scope.revision().getId(),
                                id,
                                expected == null ? null : expected.toString()));
        if (source != null)
            fields.addAll(
                    Arrays.asList(source.tool(), source.name(), source.url(), source.notes()));
        fields.addAll(Arrays.asList(payload));
        return receipts.request(
                scope.user(),
                "revision-material-" + action,
                scope.revision().getId(),
                key,
                fields.toArray(String[]::new));
    }

    private Mono<Void> record(Scope scope, Request request) {
        return receipts.record(request, scope.version(), scope.revision().getId());
    }

    private static void checkVersion(PrototypeRevisionMaterialEntity row, long expected) {
        if (row.getConfigVersion() != expected || expected == Long.MAX_VALUE) throw conflict();
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.material_conflict", "PROTOTYPE_HUB_MATERIAL_CONFLICT", 409);
    }

    private Mono<Void> queue(PrototypeRevisionMaterialEntity row) {
        return "prototype".equals(row.getMaterialType())
                ? Mono.empty()
                : cleanup.enqueueMaterial(
                        row.getProjectId(), row.getVersionId(), row.getStoragePath());
    }

    public Mono<Void> purgeVersion(String id) {
        return purge("versionId", id);
    }

    public Mono<Void> purgeProject(String id) {
        return purge("projectId", id);
    }

    private Mono<Void> purge(String field, String id) {
        return materials
                .createQuery()
                .where(field, id)
                .paging(0, 100)
                .fetch()
                .collectList()
                .flatMap(
                        rows -> {
                            if (rows.isEmpty()) return Mono.empty();
                            return Flux.fromIterable(rows)
                                    .concatMap(this::queue, 1)
                                    .then(
                                            materials
                                                    .createDelete()
                                                    .in(
                                                            PrototypeRevisionMaterialEntity::getId,
                                                            rows.stream()
                                                                    .map(
                                                                            PrototypeRevisionMaterialEntity
                                                                                    ::getId)
                                                                    .toList())
                                                    .execute())
                                    .then(Mono.defer(() -> purge(field, id)));
                        });
    }
}
