package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeDemoScriptEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeCreationReceiptService.Request;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.web.info.DemoScriptStep;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.DemoScriptRequests;
import com.gaiys.proto.prototypehub.web.response.DemoScriptResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 固定修订演示脚本的持久化边界；脚本不执行代码或自动操作原型。 写入沿账号→项目→版本锁序重验当前权限，CAS与回执同事务；清单仅在锁外解析。
 * 复用HTTP/SQL追踪且不记录正文/凭据；没有常驻状态、外部调用或新MBean。
 */
@Service
@RequiredArgsConstructor
public class PrototypeDemoScriptService {
    private static final long MAX_SAFE_VERSION = 9007199254740991L;
    private final ReactiveRepository<PrototypeDemoScriptEntity, String> scripts;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeCreationReceiptService receipts;
    private final PrototypeShareService shares;
    private final TransactionalOperator transactions;

    public Mono<PagerResult<DemoScriptResponse.Summary>> query(
            String version, String revision, DemoScriptRequests.Page input) {
        return prepare(version, revision, Action.READ)
                .flatMap(
                        scope ->
                                QueryHelper.queryPager(
                                        query(
                                                scope.fixed(),
                                                input.getPageIndex(),
                                                input.getPageSize(),
                                                false),
                                        this::summaryQuery,
                                        DemoScriptResponse.Summary::of))
                .checkpoint("prototype-hub/demo-script/query");
    }

    private ReactiveQuery<PrototypeDemoScriptEntity> summaryQuery() {
        return scripts.createQuery()
                .select(
                        PrototypeDemoScriptEntity::getId,
                        PrototypeDemoScriptEntity::getProjectId,
                        PrototypeDemoScriptEntity::getVersionId,
                        PrototypeDemoScriptEntity::getRevisionId,
                        PrototypeDemoScriptEntity::getTitle,
                        PrototypeDemoScriptEntity::getShareEnabled,
                        PrototypeDemoScriptEntity::getConfigVersion,
                        PrototypeDemoScriptEntity::getCreatedAt,
                        PrototypeDemoScriptEntity::getUpdatedAt);
    }

    public Mono<DemoScriptResponse> detail(String version, String revision, String id) {
        return prepare(version, revision, Action.READ)
                .flatMap(scope -> require(scope.fixed(), id, false).map(DemoScriptResponse::of))
                .checkpoint("prototype-hub/demo-script/detail");
    }

    public Mono<DemoScriptResponse> create(
            String version, String revision, DemoScriptRequests.Fields input) {
        return prepareFields(version, revision, input)
                .flatMap(scope -> locked(scope, () -> create(scope, input)))
                .checkpoint("prototype-hub/demo-script/create");
    }

    private Mono<DemoScriptResponse> create(Scope scope, DemoScriptRequests.Fields input) {
        var request = request(scope, "create", input.getClientRequestId(), null, null, input);
        return receipts.replay(
                        request,
                        ignored ->
                                find(scope.fixed(), request.id(), false)
                                        .map(DemoScriptResponse::of))
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    long now = System.currentTimeMillis();
                                    var row = new PrototypeDemoScriptEntity();
                                    row.setId(request.id());
                                    row.setProjectId(scope.fixed().getProjectId());
                                    row.setVersionId(scope.fixed().getVersionId());
                                    row.setRevisionId(scope.fixed().getId());
                                    fields(row, input);
                                    row.setConfigVersion(0L);
                                    row.setCreatedAt(now);
                                    row.setUpdatedAt(now);
                                    return scripts.insert(row)
                                            .then(record(scope, request))
                                            .thenReturn(DemoScriptResponse.of(row));
                                }));
    }

    public Mono<DemoScriptResponse> update(
            String version, String revision, String id, DemoScriptRequests.Update input) {
        return prepareFields(version, revision, input)
                .flatMap(scope -> locked(scope, () -> update(scope, id, input)))
                .checkpoint("prototype-hub/demo-script/update");
    }

    private Mono<DemoScriptResponse> update(
            Scope scope, String id, DemoScriptRequests.Update input) {
        var request =
                request(
                        scope,
                        "update",
                        input.getClientRequestId(),
                        id,
                        input.getExpectedVersion(),
                        input);
        return receipts.replay(
                        request,
                        ignored -> find(scope.fixed(), id, false).map(DemoScriptResponse::of))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        require(scope.fixed(), id, false)
                                                .flatMap(
                                                        row ->
                                                                update(
                                                                        scope, row, input,
                                                                        request))));
    }

    private Mono<DemoScriptResponse> update(
            Scope scope,
            PrototypeDemoScriptEntity row,
            DemoScriptRequests.Update input,
            Request request) {
        checkVersion(row, input.getExpectedVersion());
        if (row.getConfigVersion() >= MAX_SAFE_VERSION) return Mono.error(conflict());
        fields(row, input);
        row.setConfigVersion(row.getConfigVersion() + 1);
        row.setUpdatedAt(System.currentTimeMillis());
        return scripts.createUpdate()
                .set(PrototypeDemoScriptEntity::getTitle, row.getTitle())
                .set(PrototypeDemoScriptEntity::getObjective, row.getObjective())
                .set(PrototypeDemoScriptEntity::getPrerequisites, row.getPrerequisites())
                .set(PrototypeDemoScriptEntity::getResetNote, row.getResetNote())
                .set(PrototypeDemoScriptEntity::getSteps, row.getSteps())
                .set(PrototypeDemoScriptEntity::getShareEnabled, row.getShareEnabled())
                .set(PrototypeDemoScriptEntity::getConfigVersion, row.getConfigVersion())
                .set(PrototypeDemoScriptEntity::getUpdatedAt, row.getUpdatedAt())
                .where(PrototypeDemoScriptEntity::getId, row.getId())
                .and(PrototypeDemoScriptEntity::getConfigVersion, input.getExpectedVersion())
                .execute()
                .flatMap(changed -> changed == 1 ? Mono.empty() : Mono.error(conflict()))
                .then(record(scope, request))
                .thenReturn(DemoScriptResponse.of(row));
    }

    public Mono<Boolean> delete(
            String version, String revision, String id, DemoScriptRequests.Delete input) {
        return prepare(version, revision, Action.EDIT)
                .flatMap(scope -> locked(scope, () -> delete(scope, id, input)))
                .checkpoint("prototype-hub/demo-script/delete");
    }

    private Mono<Boolean> delete(Scope scope, String id, DemoScriptRequests.Delete input) {
        var request =
                request(
                        scope,
                        "delete",
                        input.getClientRequestId(),
                        id,
                        input.getExpectedVersion(),
                        null);
        // 新授权先于原键重放；删除回执保留到版本永久清理，旧创建键不能复活脚本。
        return receipts.replay(request, ignored -> Mono.just(true))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        require(scope.fixed(), id, false)
                                                .flatMap(
                                                        row ->
                                                                delete(
                                                                        scope, row, input,
                                                                        request))));
    }

    private Mono<Boolean> delete(
            Scope scope,
            PrototypeDemoScriptEntity row,
            DemoScriptRequests.Delete input,
            Request request) {
        checkVersion(row, input.getExpectedVersion());
        return scripts.createDelete()
                .where(PrototypeDemoScriptEntity::getId, row.getId())
                .and(PrototypeDemoScriptEntity::getConfigVersion, input.getExpectedVersion())
                .execute()
                .flatMap(
                        changed ->
                                changed == 1
                                        ? record(scope, request).thenReturn(true)
                                        : Mono.error(conflict()));
    }

    /** 公开读取只复用现有分享精确快照授权，不写访客、会话、访问量或批准状态。 */
    public Mono<PagerResult<DemoScriptResponse.PublicSummary>> publicQuery(
            String token, DemoScriptRequests.PublicQuery input) {
        return shares.requireRevisionAccess(token, input.getAccessKey(), input.getRevisionId())
                .flatMap(
                        fixed ->
                                QueryHelper.queryPager(
                                        query(
                                                fixed,
                                                input.getPageIndex(),
                                                input.getPageSize(),
                                                true),
                                        () ->
                                                scripts.createQuery()
                                                        .select(
                                                                PrototypeDemoScriptEntity::getId,
                                                                PrototypeDemoScriptEntity
                                                                        ::getRevisionId,
                                                                PrototypeDemoScriptEntity
                                                                        ::getTitle),
                                        DemoScriptResponse.PublicSummary::of))
                .checkpoint("prototype-hub/demo-script/public-query");
    }

    public Mono<DemoScriptResponse.PublicContext> publicContext(
            String token, String id, DemoScriptRequests.PublicContext input) {
        return shares.requireRevisionAccess(token, input.getAccessKey(), input.getRevisionId())
                .flatMap(
                        fixed -> require(fixed, id, true).map(DemoScriptResponse.PublicContext::of))
                .checkpoint("prototype-hub/demo-script/public-context");
    }

    private QueryParamEntity query(
            PrototypeRevisionEntity fixed, int page, int size, boolean shared) {
        var builder =
                QueryParamEntity.newQuery()
                        .where(PrototypeDemoScriptEntity::getProjectId, fixed.getProjectId())
                        .and(PrototypeDemoScriptEntity::getVersionId, fixed.getVersionId())
                        .and(PrototypeDemoScriptEntity::getRevisionId, fixed.getId());
        if (shared) builder.and(PrototypeDemoScriptEntity::getShareEnabled, true);
        // 范围及公开开关同样进入COUNT和LIMIT；列表SQL不读取正文或步骤JSON。
        var query =
                builder.orderByDesc(PrototypeDemoScriptEntity::getCreatedAt)
                        .orderByAsc(PrototypeDemoScriptEntity::getId)
                        .getParam();
        query.doPaging(page, size);
        query.setParallelPager(true);
        return query;
    }

    private Mono<Scope> prepare(String version, String revision, Action action) {
        return access.current().flatMap(actor -> authorize(actor, version, revision, action, true));
    }

    private Mono<Scope> authorize(
            Actor actor, String version, String revision, Action action, boolean audit) {
        if (!actor.authentication()
                .hasPermission("prototype-hub-version", action == Action.READ ? "query" : "save"))
            return Mono.error(PrototypeProjectAccessService.roleDenied());
        return access.requireVersion(actor, version, action, audit)
                .flatMap(row -> revisions.require(row, revision))
                .map(fixed -> new Scope(actor.userId(), fixed));
    }

    private Mono<Scope> prepareFields(
            String version, String revision, DemoScriptRequests.Fields input) {
        return prepare(version, revision, Action.EDIT)
                .flatMap(
                        scope ->
                                revisions
                                        .pages(scope.fixed())
                                        .map(
                                                pages -> {
                                                    validateSteps(input.getSteps(), pages);
                                                    return scope;
                                                }));
    }

    static void validateSteps(List<DemoScriptStep> steps, List<PrototypePageInfo> pages) {
        Set<String> real = new HashSet<>();
        pageIds(pages, real);
        Set<String> identities = new HashSet<>();
        for (DemoScriptStep step : steps) {
            if (!identities.add(step.id())) throw invalidSteps();
            if (!real.contains(step.pageId())) throw PrototypeHubException.missing("page");
        }
    }

    private static void pageIds(List<PrototypePageInfo> pages, Set<String> result) {
        for (PrototypePageInfo page : pages) {
            // 可打开父页即使带children也有效，只有目录节点不可作为演示页面。
            if (!page.folder()) result.add(page.pageId());
            pageIds(page.children(), result);
        }
    }

    private <T> Mono<T> locked(Scope scope, Supplier<Mono<T>> work) {
        return access.lockActiveUsers(scope.user())
                .then(access.lockProject(scope.fixed().getProjectId()))
                .then(access.lockVersion(scope.fixed().getVersionId()))
                .then(access.actor(scope.user()))
                .flatMap(
                        actor ->
                                authorize(
                                        actor,
                                        scope.fixed().getVersionId(),
                                        scope.fixed().getId(),
                                        Action.EDIT,
                                        false))
                .filter(
                        current ->
                                scope.fixed().getProjectId().equals(current.fixed().getProjectId()))
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("revision")))
                .then(Mono.defer(work))
                .as(transactions::transactional);
    }

    private Mono<PrototypeDemoScriptEntity> find(
            PrototypeRevisionEntity fixed, String id, boolean shared) {
        var query =
                scripts.createQuery()
                        .where(PrototypeDemoScriptEntity::getId, id)
                        .and(PrototypeDemoScriptEntity::getProjectId, fixed.getProjectId())
                        .and(PrototypeDemoScriptEntity::getVersionId, fixed.getVersionId())
                        .and(PrototypeDemoScriptEntity::getRevisionId, fixed.getId());
        if (shared) query.and(PrototypeDemoScriptEntity::getShareEnabled, true);
        return query.fetch().singleOrEmpty();
    }

    private Mono<PrototypeDemoScriptEntity> require(
            PrototypeRevisionEntity fixed, String id, boolean shared) {
        return find(fixed, id, shared)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("demo_script")));
    }

    private Request request(
            Scope scope,
            String action,
            String key,
            String id,
            Long expected,
            DemoScriptRequests.Fields input) {
        var fixed = scope.fixed();
        var values =
                new ArrayList<String>(
                        Arrays.asList(
                                fixed.getProjectId(),
                                fixed.getVersionId(),
                                fixed.getId(),
                                id,
                                expected == null ? null : expected.toString()));
        if (input != null) {
            values.addAll(
                    List.of(
                            input.getTitle(),
                            input.getObjective(),
                            plain(input.getPrerequisites()),
                            plain(input.getResetNote()),
                            input.getShareEnabled().toString()));
            for (DemoScriptStep step : input.getSteps())
                values.addAll(List.of(step.id(), step.pageId(), step.narration()));
        }
        return receipts.request(
                scope.user(),
                "demo-script-" + action,
                fixed.getId(),
                key,
                values.toArray(String[]::new));
    }

    private Mono<Void> record(Scope scope, Request request) {
        return receipts.record(request, scope.fixed().getVersionId(), scope.fixed().getId());
    }

    private static void fields(PrototypeDemoScriptEntity row, DemoScriptRequests.Fields input) {
        row.setTitle(input.getTitle());
        row.setObjective(input.getObjective());
        row.setPrerequisites(plain(input.getPrerequisites()));
        row.setResetNote(plain(input.getResetNote()));
        row.setSteps(List.copyOf(input.getSteps()));
        row.setShareEnabled(input.getShareEnabled());
    }

    private static String plain(String value) {
        return Objects.toString(value, "");
    }

    private static void checkVersion(PrototypeDemoScriptEntity row, long expected) {
        if (row.getConfigVersion() != expected) throw conflict();
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.demo_script_conflict",
                "PROTOTYPE_HUB_DEMO_SCRIPT_CONFLICT",
                409);
    }

    private static PrototypeHubException invalidSteps() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.demo_script_steps_invalid",
                "PROTOTYPE_HUB_DEMO_SCRIPT_STEPS_INVALID");
    }

    private record Scope(String user, PrototypeRevisionEntity fixed) {}
}
