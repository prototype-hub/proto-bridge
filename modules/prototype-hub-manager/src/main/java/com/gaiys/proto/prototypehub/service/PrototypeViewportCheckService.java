package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeViewportCheckEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeCreationReceiptService.Request;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.ViewportCheckRequests;
import com.gaiys.proto.prototypehub.web.response.ViewportCheckResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 固定页人工记录的持久化边界。写入按账号→项目→版本锁序，当前权限、CAS与持久回执同事务。
 * 清单解析在锁外沿既有FileWork完成，锁内不做文件I/O；复用HTTP/SQL追踪，不记录场景正文或请求键。 无自动测试、外部I/O、常驻任务或新MBean。
 */
@Service
@RequiredArgsConstructor
public class PrototypeViewportCheckService {
    private final ReactiveRepository<PrototypeViewportCheckEntity, String> checks;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeCreationReceiptService receipts;
    private final TransactionalOperator transactions;

    private record Scope(
            String user, String project, String version, String revision, String page) {}

    public Mono<PagerResult<ViewportCheckResponse>> query(
            String version, String revision, String page, ViewportCheckRequests.Query input) {
        return prepare(version, revision, page, Action.READ)
                .flatMap(
                        scope -> {
                            var builder =
                                    QueryParamEntity.newQuery()
                                            .where(
                                                    PrototypeViewportCheckEntity::getProjectId,
                                                    scope.project())
                                            .and(
                                                    PrototypeViewportCheckEntity::getVersionId,
                                                    version)
                                            .and(
                                                    PrototypeViewportCheckEntity::getRevisionId,
                                                    revision)
                                            .and(PrototypeViewportCheckEntity::getPageId, page);
                            if (input.getCategory() != null)
                                builder.and(
                                        PrototypeViewportCheckEntity::getCategory,
                                        input.getCategory());
                            if (input.getResult() != null)
                                builder.and(
                                        PrototypeViewportCheckEntity::getResult, input.getResult());
                            QueryParamEntity query =
                                    builder.orderByDesc(PrototypeViewportCheckEntity::getCreatedAt)
                                            .orderByAsc(PrototypeViewportCheckEntity::getId)
                                            .getParam();
                            query.doPaging(input.getPageIndex(), input.getPageSize());
                            query.setParallelPager(true);
                            return QueryHelper.queryPager(
                                    query, checks::createQuery, ViewportCheckResponse::of);
                        })
                .checkpoint("prototype-hub/viewport-check/query");
    }

    public Mono<ViewportCheckResponse> create(
            String version, String revision, String page, ViewportCheckRequests.Fields input) {
        return prepare(version, revision, page, Action.EDIT)
                .flatMap(scope -> locked(scope, () -> create(scope, input)))
                .checkpoint("prototype-hub/viewport-check/create");
    }

    private Mono<ViewportCheckResponse> create(Scope scope, ViewportCheckRequests.Fields input) {
        Request request = request(scope, "create", input.getClientRequestId(), null, null, input);
        return receipts.replay(
                        request,
                        ignored -> find(scope, request.id()).map(ViewportCheckResponse::of))
                .switchIfEmpty(Mono.defer(() -> insert(scope, input, request)));
    }

    private Mono<ViewportCheckResponse> insert(
            Scope scope, ViewportCheckRequests.Fields input, Request request) {
        long now = System.currentTimeMillis();
        var row = new PrototypeViewportCheckEntity();
        row.setId(request.id());
        row.setProjectId(scope.project());
        row.setVersionId(scope.version());
        row.setRevisionId(scope.revision());
        row.setPageId(scope.page());
        fields(row, input);
        row.setConfigVersion(0L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return checks.insert(row)
                .then(record(scope, request))
                .thenReturn(ViewportCheckResponse.of(row));
    }

    public Mono<ViewportCheckResponse> update(
            String version,
            String revision,
            String page,
            String id,
            ViewportCheckRequests.Update input) {
        return prepare(version, revision, page, Action.EDIT)
                .flatMap(scope -> locked(scope, () -> update(scope, id, input)))
                .checkpoint("prototype-hub/viewport-check/update");
    }

    private Mono<ViewportCheckResponse> update(
            Scope scope, String id, ViewportCheckRequests.Update input) {
        Request request =
                request(
                        scope,
                        "update",
                        input.getClientRequestId(),
                        id,
                        input.getExpectedVersion(),
                        input);
        return receipts.replay(request, ignored -> find(scope, id).map(ViewportCheckResponse::of))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        require(scope, id)
                                                .flatMap(
                                                        row ->
                                                                update(
                                                                        scope, row, input,
                                                                        request))));
    }

    private Mono<ViewportCheckResponse> update(
            Scope scope,
            PrototypeViewportCheckEntity row,
            ViewportCheckRequests.Update input,
            Request request) {
        checkVersion(row, input.getExpectedVersion());
        fields(row, input);
        row.setConfigVersion(row.getConfigVersion() + 1);
        row.setUpdatedAt(System.currentTimeMillis());
        return checks.createUpdate()
                .set(PrototypeViewportCheckEntity::getViewportWidth, row.getViewportWidth())
                .set(PrototypeViewportCheckEntity::getViewportHeight, row.getViewportHeight())
                .set(PrototypeViewportCheckEntity::getViewportScale, row.getViewportScale())
                .set(PrototypeViewportCheckEntity::getCategory, row.getCategory())
                .set(PrototypeViewportCheckEntity::getTitle, row.getTitle())
                .set(PrototypeViewportCheckEntity::getSteps, row.getSteps())
                .set(PrototypeViewportCheckEntity::getExpected, row.getExpected())
                .set(PrototypeViewportCheckEntity::getObserved, row.getObserved())
                .set(PrototypeViewportCheckEntity::getResult, row.getResult())
                .set(PrototypeViewportCheckEntity::getEvidence, row.getEvidence())
                .set(PrototypeViewportCheckEntity::getConfigVersion, row.getConfigVersion())
                .set(PrototypeViewportCheckEntity::getUpdatedAt, row.getUpdatedAt())
                .where(PrototypeViewportCheckEntity::getId, row.getId())
                .and(PrototypeViewportCheckEntity::getConfigVersion, input.getExpectedVersion())
                .execute()
                .flatMap(changed -> changed == 1 ? Mono.empty() : Mono.error(conflict()))
                .then(record(scope, request))
                .thenReturn(ViewportCheckResponse.of(row));
    }

    public Mono<Boolean> delete(
            String version,
            String revision,
            String page,
            String id,
            ViewportCheckRequests.Delete input) {
        return prepare(version, revision, page, Action.EDIT)
                .flatMap(scope -> locked(scope, () -> delete(scope, id, input)))
                .checkpoint("prototype-hub/viewport-check/delete");
    }

    private Mono<Boolean> delete(Scope scope, String id, ViewportCheckRequests.Delete input) {
        Request request =
                request(
                        scope,
                        "delete",
                        input.getClientRequestId(),
                        id,
                        input.getExpectedVersion(),
                        null);
        // 当前授权先于回执；已完成删除可重放，但创建旧键不能复活被删记录。
        return receipts.replay(request, ignored -> Mono.just(true))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        require(scope, id)
                                                .flatMap(
                                                        row ->
                                                                delete(
                                                                        scope, row, input,
                                                                        request))));
    }

    private Mono<Boolean> delete(
            Scope scope,
            PrototypeViewportCheckEntity row,
            ViewportCheckRequests.Delete input,
            Request request) {
        checkVersion(row, input.getExpectedVersion());
        return checks.createDelete()
                .where(PrototypeViewportCheckEntity::getId, row.getId())
                .and(PrototypeViewportCheckEntity::getConfigVersion, input.getExpectedVersion())
                .execute()
                .flatMap(
                        changed ->
                                changed == 1
                                        ? record(scope, request).thenReturn(true)
                                        : Mono.error(conflict()));
    }

    private Mono<Scope> prepare(String version, String revision, String page, Action action) {
        return access.current()
                .flatMap(
                        actor ->
                                authorize(actor.userId(), version, action, true)
                                        .flatMap(
                                                row ->
                                                        revisions
                                                                .require(row, revision)
                                                                .flatMap(revisions::pages)
                                                                .filter(
                                                                        pages ->
                                                                                contains(
                                                                                        pages,
                                                                                        page))
                                                                .switchIfEmpty(
                                                                        Mono.error(
                                                                                () ->
                                                                                        PrototypeHubException
                                                                                                .missing(
                                                                                                        "page")))
                                                                .thenReturn(
                                                                        new Scope(
                                                                                actor.userId(),
                                                                                row.getProjectId(),
                                                                                version,
                                                                                revision,
                                                                                page))));
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
                .flatMap(version -> revisions.require(version, scope.revision()))
                .then(Mono.defer(operation))
                .as(transactions::transactional);
    }

    private Mono<PrototypeViewportCheckEntity> find(Scope scope, String id) {
        return checks.createQuery()
                .where(PrototypeViewportCheckEntity::getId, id)
                .and(PrototypeViewportCheckEntity::getProjectId, scope.project())
                .and(PrototypeViewportCheckEntity::getVersionId, scope.version())
                .and(PrototypeViewportCheckEntity::getRevisionId, scope.revision())
                .and(PrototypeViewportCheckEntity::getPageId, scope.page())
                .fetch()
                .singleOrEmpty();
    }

    private Mono<PrototypeViewportCheckEntity> require(Scope scope, String id) {
        return find(scope, id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("viewport_check")));
    }

    private Request request(
            Scope scope,
            String action,
            String key,
            String id,
            Long expected,
            ViewportCheckRequests.Fields input) {
        var values =
                new ArrayList<String>(
                        Arrays.asList(
                                scope.project(),
                                scope.version(),
                                scope.revision(),
                                scope.page(),
                                id,
                                expected == null ? null : expected.toString()));
        if (input != null)
            values.addAll(
                    List.of(
                            input.getViewportWidth().toString(),
                            input.getViewportHeight().toString(),
                            input.getViewportScale().toString(),
                            input.getCategory(),
                            input.getTitle(),
                            plain(input.getSteps()),
                            input.getExpected(),
                            plain(input.getObserved()),
                            input.getResult(),
                            plain(input.getEvidence())));
        return receipts.request(
                scope.user(),
                "viewport-check-" + action,
                scope.revision() + ":" + scope.page(),
                key,
                values.toArray(String[]::new));
    }

    private Mono<Void> record(Scope scope, Request request) {
        return receipts.record(request, scope.version(), scope.revision());
    }

    private static void fields(
            PrototypeViewportCheckEntity row, ViewportCheckRequests.Fields input) {
        row.setViewportWidth(input.getViewportWidth());
        row.setViewportHeight(input.getViewportHeight());
        row.setViewportScale(input.getViewportScale());
        row.setCategory(input.getCategory());
        row.setTitle(input.getTitle());
        row.setSteps(plain(input.getSteps()));
        row.setExpected(input.getExpected());
        row.setObserved(plain(input.getObserved()));
        row.setResult(input.getResult());
        row.setEvidence(plain(input.getEvidence()));
    }

    private static String plain(String value) {
        return Objects.toString(value, "");
    }

    private static void checkVersion(PrototypeViewportCheckEntity row, long expected) {
        if (row.getConfigVersion() != expected || expected == Long.MAX_VALUE) throw conflict();
    }

    private static boolean contains(List<PrototypePageInfo> pages, String id) {
        return pages.stream()
                .anyMatch(
                        page ->
                                (!page.folder() && id.equals(page.pageId()))
                                        || contains(page.children(), id));
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.viewport_check_conflict",
                "PROTOTYPE_HUB_VIEWPORT_CHECK_CONFLICT",
                409);
    }
}
