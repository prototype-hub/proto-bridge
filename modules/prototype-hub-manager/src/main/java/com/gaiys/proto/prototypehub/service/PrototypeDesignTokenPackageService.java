package com.gaiys.proto.prototypehub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeDesignTokenPackageEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeCreationReceiptService.Request;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.validation.RevisionMaterialSource;
import com.gaiys.proto.prototypehub.web.request.DesignTokenPackageRequests;
import com.gaiys.proto.prototypehub.web.request.RevisionMaterialRequests;
import com.gaiys.proto.prototypehub.web.response.DesignTokenDocument;
import com.gaiys.proto.prototypehub.web.response.DesignTokenPackageImportResponse;
import com.gaiys.proto.prototypehub.web.response.DesignTokenPackageResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
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

/** 固定修订规范包的唯一持久化边界；来源CAS与不可变原始JSON分离。 */
@Service
@RequiredArgsConstructor
public class PrototypeDesignTokenPackageService {
    public static final int MAX_PACKAGES = 100;
    private final ReactiveRepository<PrototypeDesignTokenPackageEntity, String> packages;
    private final PrototypeDesignTokenPackageFileService files;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeCreationReceiptService receipts;
    private final FileCleanupService cleanup;
    private final TransactionalOperator transactions;
    private final ObjectMapper json;

    private record Scope(
            String user, String project, String version, PrototypeRevisionEntity revision) {}

    public Mono<DesignTokenDocument> validate(String version, String revision, FilePart file) {
        return prepare(version, revision, Action.EDIT)
                .flatMap(
                        scope ->
                                files.validate(file)
                                        .flatMap(
                                                document ->
                                                        prepare(version, revision, Action.EDIT)
                                                                .thenReturn(document)));
    }

    public Mono<DesignTokenPackageImportResponse> importPackage(
            String version,
            String revision,
            FilePart file,
            RevisionMaterialRequests.Source input,
            String allowInvalid,
            String key) {
        return Mono.defer(
                        () -> {
                            var source = RevisionMaterialSource.of(input);
                            if (!"true".equals(allowInvalid) && !"false".equals(allowInvalid))
                                return Mono.error(
                                        PrototypeHubException.badRequest(
                                                "error.prototype-hub.token_package_invalid",
                                                "PROTOTYPE_HUB_TOKEN_PACKAGE_INVALID"));
                            // 新导入必须有回执键，未知结果才能保留原文件/原键进行精确重试。
                            if (key == null)
                                return Mono.error(
                                        PrototypeHubException.badRequest(
                                                "error.prototype-hub.idempotency_key_invalid",
                                                "PROTOTYPE_HUB_IDEMPOTENCY_KEY_INVALID"));
                            boolean acknowledged = Boolean.parseBoolean(allowInvalid);
                            return prepare(version, revision, Action.EDIT)
                                    .flatMap(
                                            scope ->
                                                    files.upload(
                                                            scope.project(),
                                                            version,
                                                            file,
                                                            acknowledged,
                                                            candidate ->
                                                                    locked(
                                                                            scope,
                                                                            () ->
                                                                                    create(
                                                                                            scope,
                                                                                            candidate,
                                                                                            source,
                                                                                            acknowledged,
                                                                                            key))));
                        })
                .checkpoint("prototype-hub/token-package/import");
    }

    private Mono<PrototypeDesignTokenPackageFileService.Stored<DesignTokenPackageImportResponse>>
            create(
                    Scope scope,
                    PrototypeDesignTokenPackageFileService.Candidate candidate,
                    RevisionMaterialSource source,
                    boolean allowInvalid,
                    String key) {
        Request request =
                request(
                        scope,
                        "import",
                        key,
                        null,
                        null,
                        source,
                        candidate.fileName(),
                        Long.toString(candidate.size()),
                        candidate.sha256(),
                        Boolean.toString(allowInvalid));
        String id = request.id();
        return receipts.replay(
                        request,
                        ignored ->
                                find(scope, id)
                                        .map(
                                                row ->
                                                        new PrototypeDesignTokenPackageFileService
                                                                .Stored<>(
                                                                new DesignTokenPackageImportResponse(
                                                                        id,
                                                                        "current",
                                                                        response(row)),
                                                                row.getStoragePath()))
                                        .defaultIfEmpty(
                                                new PrototypeDesignTokenPackageFileService.Stored<>(
                                                        new DesignTokenPackageImportResponse(
                                                                id, "missing", null),
                                                        null)))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        packages.createQuery()
                                                .where(
                                                        PrototypeDesignTokenPackageEntity
                                                                ::getRevisionId,
                                                        scope.revision().getId())
                                                .count()
                                                .flatMap(
                                                        count -> {
                                                            if (count >= MAX_PACKAGES)
                                                                return Mono.error(
                                                                        new PrototypeHubException(
                                                                                "error.prototype-hub.token_package_limit_reached",
                                                                                "PROTOTYPE_HUB_TOKEN_PACKAGE_LIMIT_REACHED",
                                                                                409));
                                                            var row =
                                                                    row(
                                                                            scope, id, candidate,
                                                                            source);
                                                            return packages.insert(row)
                                                                    .then(record(scope, request))
                                                                    .thenReturn(
                                                                            new PrototypeDesignTokenPackageFileService
                                                                                    .Stored<>(
                                                                                    new DesignTokenPackageImportResponse(
                                                                                            id,
                                                                                            "current",
                                                                                            response(
                                                                                                    row)),
                                                                                    row
                                                                                            .getStoragePath()));
                                                        })));
    }

    public Mono<PagerResult<DesignTokenPackageResponse>> query(
            String version, String revision, DesignTokenPackageRequests.Query input) {
        return prepare(version, revision, Action.READ)
                .flatMap(
                        scope -> {
                            var query =
                                    QueryParamEntity.newQuery()
                                            .where(
                                                    PrototypeDesignTokenPackageEntity::getProjectId,
                                                    scope.project())
                                            .and(
                                                    PrototypeDesignTokenPackageEntity::getVersionId,
                                                    scope.version())
                                            .and(
                                                    PrototypeDesignTokenPackageEntity
                                                            ::getRevisionId,
                                                    scope.revision().getId())
                                            .orderByDesc(
                                                    PrototypeDesignTokenPackageEntity::getCreatedAt)
                                            .orderByAsc(PrototypeDesignTokenPackageEntity::getId)
                                            .getParam();
                            query.doPaging(input.getPageIndex(), input.getPageSize());
                            query.setParallelPager(true);
                            return QueryHelper.queryPager(
                                    query, packages::createQuery, this::response);
                        });
    }

    public Mono<DesignTokenPackageResponse> detail(String version, String revision, String id) {
        return prepare(version, revision, Action.READ)
                .flatMap(scope -> require(scope, id))
                .map(this::response);
    }

    /** 原文件读取/解析不持锁；返回前重新读取当前授权和同一原字节归属。 */
    public Mono<DesignTokenDocument> document(
            String version, String revision, String id, DesignTokenPackageRequests.Document input) {
        return prepare(version, revision, Action.READ)
                .flatMap(scope -> require(scope, id))
                .flatMap(
                        row ->
                                files.document(row, input.getInputs())
                                        .flatMap(
                                                document ->
                                                        currentOriginal(version, revision, row)
                                                                .thenReturn(document)));
    }

    public Mono<ResponseEntity<Resource>> read(String version, String revision, String id) {
        return prepare(version, revision, Action.READ)
                .flatMap(scope -> require(scope, id))
                .flatMap(
                        row ->
                                files.read(row)
                                        .flatMap(
                                                content ->
                                                        currentOriginal(version, revision, row)
                                                                .thenReturn(content)));
    }

    private Mono<Void> currentOriginal(
            String version, String revision, PrototypeDesignTokenPackageEntity original) {
        return prepare(version, revision, Action.READ)
                .flatMap(scope -> require(scope, original.getId()))
                .filter(
                        row ->
                                Objects.equals(row.getSha256(), original.getSha256())
                                        && Objects.equals(
                                                row.getStoragePath(), original.getStoragePath()))
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("token_package")))
                .then();
    }

    public Mono<DesignTokenPackageResponse> update(
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
                                                                        input.getClientRequestId(),
                                                                        id,
                                                                        input.getExpectedVersion(),
                                                                        source);
                                                        return receipts.replay(
                                                                        request,
                                                                        ignored -> find(scope, id))
                                                                .switchIfEmpty(
                                                                        Mono.defer(
                                                                                () ->
                                                                                        require(
                                                                                                        scope,
                                                                                                        id)
                                                                                                .flatMap(
                                                                                                        row ->
                                                                                                                updateSource(
                                                                                                                        scope,
                                                                                                                        row,
                                                                                                                        source,
                                                                                                                        input
                                                                                                                                .getExpectedVersion(),
                                                                                                                        request))))
                                                                .map(this::response);
                                                    }));
                });
    }

    private Mono<PrototypeDesignTokenPackageEntity> updateSource(
            Scope scope,
            PrototypeDesignTokenPackageEntity row,
            RevisionMaterialSource source,
            long expected,
            Request request) {
        checkVersion(row, expected);
        applySource(row, source);
        row.setConfigVersion(expected + 1);
        row.setUpdatedAt(System.currentTimeMillis());
        row.setUpdatedBy(scope.user());
        var update =
                packages.createUpdate()
                        .set(row::getSourceTool)
                        .set(row::getSourceName)
                        .set(row::getSourceUrl)
                        .set(row::getSourceNotes)
                        .set(row::getConfigVersion)
                        .set(row::getUpdatedAt)
                        .set(row::getUpdatedBy);
        if (row.getSourceTool() == null)
            update.setNull(PrototypeDesignTokenPackageEntity::getSourceTool);
        if (row.getSourceName() == null)
            update.setNull(PrototypeDesignTokenPackageEntity::getSourceName);
        if (row.getSourceUrl() == null)
            update.setNull(PrototypeDesignTokenPackageEntity::getSourceUrl);
        if (row.getSourceNotes() == null)
            update.setNull(PrototypeDesignTokenPackageEntity::getSourceNotes);
        return update.where(PrototypeDesignTokenPackageEntity::getId, row.getId())
                .and(PrototypeDesignTokenPackageEntity::getConfigVersion, expected)
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
                                                                                                                packages.createDelete()
                                                                                                                        .where(
                                                                                                                                PrototypeDesignTokenPackageEntity
                                                                                                                                        ::getId,
                                                                                                                                id)
                                                                                                                        .and(
                                                                                                                                PrototypeDesignTokenPackageEntity
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
                                        }));
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

    private Mono<PrototypeDesignTokenPackageEntity> find(Scope scope, String id) {
        return packages.createQuery()
                .where(PrototypeDesignTokenPackageEntity::getId, id)
                .and(PrototypeDesignTokenPackageEntity::getProjectId, scope.project())
                .and(PrototypeDesignTokenPackageEntity::getVersionId, scope.version())
                .and(PrototypeDesignTokenPackageEntity::getRevisionId, scope.revision().getId())
                .fetch()
                .singleOrEmpty();
    }

    private Mono<PrototypeDesignTokenPackageEntity> require(Scope scope, String id) {
        return find(scope, id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("token_package")));
    }

    private PrototypeDesignTokenPackageEntity row(
            Scope scope,
            String id,
            PrototypeDesignTokenPackageFileService.Candidate candidate,
            RevisionMaterialSource source) {
        var row = new PrototypeDesignTokenPackageEntity();
        row.setId(id);
        row.setProjectId(scope.project());
        row.setVersionId(scope.version());
        row.setRevisionId(scope.revision().getId());
        row.setFileName(candidate.fileName());
        row.setFileSize(candidate.size());
        row.setSha256(candidate.sha256());
        row.setStoragePath(candidate.path());
        var document = candidate.document();
        row.setStandardVersion(document.getStandardVersion());
        row.setValidatorVersion(document.getValidatorVersion());
        row.setValid(document.isValid());
        row.setTokenCount(document.getTokenCount());
        row.setGroupCount(document.getGroupCount());
        row.setIssueCount(document.getIssues().size());
        try {
            row.setReport(json.writeValueAsString(DesignTokenPackageResponse.Report.of(document)));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to encode token report", error);
        }
        applySource(row, source);
        row.setConfigVersion(0L);
        row.setCreatedBy(scope.user());
        row.setUpdatedBy(scope.user());
        row.setCreatedAt(System.currentTimeMillis());
        row.setUpdatedAt(row.getCreatedAt());
        return row;
    }

    private static void applySource(
            PrototypeDesignTokenPackageEntity row, RevisionMaterialSource source) {
        row.setSourceTool(source.tool());
        row.setSourceName(source.name());
        row.setSourceUrl(source.url());
        row.setSourceNotes(source.notes());
    }

    private DesignTokenPackageResponse response(PrototypeDesignTokenPackageEntity row) {
        try {
            return new DesignTokenPackageResponse(
                    row.getId(),
                    row.getProjectId(),
                    row.getVersionId(),
                    row.getRevisionId(),
                    row.getFileName(),
                    row.getFileSize(),
                    row.getSha256(),
                    row.getStandardVersion(),
                    row.getValidatorVersion(),
                    row.getValid(),
                    row.getTokenCount(),
                    row.getGroupCount(),
                    row.getIssueCount(),
                    json.readValue(row.getReport(), DesignTokenPackageResponse.Report.class),
                    row.getSourceTool(),
                    row.getSourceName(),
                    row.getSourceUrl(),
                    row.getSourceNotes(),
                    row.getConfigVersion(),
                    row.getCreatedBy(),
                    row.getUpdatedBy(),
                    row.getCreatedAt(),
                    row.getUpdatedAt());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to decode token report", error);
        }
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
                new ArrayList<>(
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
                "token-package-" + action,
                scope.revision().getId(),
                key,
                fields.toArray(String[]::new));
    }

    private Mono<Void> record(Scope scope, Request request) {
        return receipts.record(request, scope.version(), scope.revision().getId());
    }

    private static void checkVersion(PrototypeDesignTokenPackageEntity row, long expected) {
        if (row.getConfigVersion() != expected || expected == Long.MAX_VALUE) throw conflict();
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.token_package_conflict",
                "PROTOTYPE_HUB_TOKEN_PACKAGE_CONFLICT",
                409);
    }

    private Mono<Void> queue(PrototypeDesignTokenPackageEntity row) {
        return cleanup.enqueueTokenPackage(
                row.getProjectId(), row.getVersionId(), row.getStoragePath());
    }

    public Mono<Void> purgeVersion(String id) {
        return purge("versionId", id);
    }

    public Mono<Void> purgeProject(String id) {
        return purge("projectId", id);
    }

    /** 父资源永久清理的事务内分批删引用并登记文件队列；软删不进入此入口。 */
    private Mono<Void> purge(String field, String id) {
        return packages.createQuery()
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
                                            packages.createDelete()
                                                    .in(
                                                            PrototypeDesignTokenPackageEntity
                                                                    ::getId,
                                                            rows.stream()
                                                                    .map(
                                                                            PrototypeDesignTokenPackageEntity
                                                                                    ::getId)
                                                                    .toList())
                                                    .execute())
                                    .then(Mono.defer(() -> purge(field, id)));
                        });
    }
}
