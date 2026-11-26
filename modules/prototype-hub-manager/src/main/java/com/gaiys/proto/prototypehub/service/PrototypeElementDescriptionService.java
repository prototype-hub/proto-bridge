package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.configuration.builder.PrototypeTextTermFragmentBuilder;
import com.gaiys.proto.prototypehub.entity.PrototypeElementDescriptionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.ElementDescriptionRequests;
import com.gaiys.proto.prototypehub.web.response.ElementDescriptionResponse;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** 固定修订页面的人工功能说明，文本及几何不作DOM身份或授权依据。 写入沿活跃用户→项目→版本锁序，与持久回执同事务；复用HTTP/SQL追踪，不记录正文，无常驻状态或新MBean。 */
@Service
@RequiredArgsConstructor
public class PrototypeElementDescriptionService {
    private final ReactiveRepository<PrototypeElementDescriptionEntity, String> descriptions;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeCreationReceiptService receipts;
    private final TransactionalOperator transactions;

    public Mono<ElementDescriptionResponse> detail(
            String versionId, String id, ElementDescriptionRequests.Scope input) {
        return prepare(versionId, input, Action.READ)
                .flatMap(scope -> require(scope, input, id).map(ElementDescriptionResponse::of))
                .checkpoint("prototype-hub/element-description/detail");
    }

    public Mono<PagerResult<ElementDescriptionResponse>> query(
            String versionId, ElementDescriptionRequests.Query input) {
        return prepare(versionId, input, Action.READ)
                .flatMap(
                        scope -> {
                            var builder =
                                    QueryParamEntity.newQuery()
                                            .where(
                                                    PrototypeElementDescriptionEntity::getProjectId,
                                                    scope.version().getProjectId())
                                            .and(
                                                    PrototypeElementDescriptionEntity::getVersionId,
                                                    versionId)
                                            .and(
                                                    PrototypeElementDescriptionEntity
                                                            ::getRevisionId,
                                                    input.getRevisionId())
                                            .and(
                                                    PrototypeElementDescriptionEntity::getPageId,
                                                    input.getPageId());
                            if (input.getKeyword() != null && !input.getKeyword().isEmpty())
                                builder.and(
                                        PrototypeElementDescriptionEntity::getTitle,
                                        PrototypeTextTermFragmentBuilder.LITERAL_CONTAINS
                                                .getTermType(),
                                        input.getKeyword());
                            var query =
                                    builder.orderByDesc(
                                                    PrototypeElementDescriptionEntity::getCreatedAt)
                                            .orderByAsc(PrototypeElementDescriptionEntity::getId)
                                            .getParam();
                            query.doPaging(input.getPageIndex(), input.getPageSize());
                            query.setParallelPager(true);
                            // 所有权限/范围/标题条件先于COUNT及LIMIT，不在内存过滤分页结果。
                            return QueryHelper.queryPager(
                                    query,
                                    descriptions::createQuery,
                                    ElementDescriptionResponse::of);
                        })
                .checkpoint("prototype-hub/element-description/query");
    }

    public Mono<ElementDescriptionResponse> create(
            String versionId, ElementDescriptionRequests.Create input) {
        return prepare(versionId, input, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        input,
                                        () -> {
                                            var request =
                                                    request(
                                                            scope,
                                                            input,
                                                            "create",
                                                            input.getClientRequestId(),
                                                            null,
                                                            null,
                                                            input);
                                            // 请求身份确定主键；旧创建键遇到已删行返回冲突，不复活说明。
                                            return receipts.replay(
                                                            request,
                                                            ignored ->
                                                                    find(scope, input, request.id())
                                                                            .map(
                                                                                    ElementDescriptionResponse
                                                                                            ::of))
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () -> {
                                                                        long now =
                                                                                System
                                                                                        .currentTimeMillis();
                                                                        var row =
                                                                                new PrototypeElementDescriptionEntity();
                                                                        row.setId(request.id());
                                                                        row.setProjectId(
                                                                                scope.version()
                                                                                        .getProjectId());
                                                                        row.setVersionId(versionId);
                                                                        row.setRevisionId(
                                                                                input
                                                                                        .getRevisionId());
                                                                        row.setPageId(
                                                                                input.getPageId());
                                                                        copyText(input, row);
                                                                        row.setConfigVersion(0L);
                                                                        row.setCreatedAt(now);
                                                                        row.setUpdatedAt(now);
                                                                        return descriptions
                                                                                .insert(row)
                                                                                .then(
                                                                                        record(
                                                                                                scope,
                                                                                                input,
                                                                                                request))
                                                                                .thenReturn(
                                                                                        ElementDescriptionResponse
                                                                                                .of(
                                                                                                        row));
                                                                    }));
                                        }))
                .checkpoint("prototype-hub/element-description/create");
    }

    public Mono<ElementDescriptionResponse> update(
            String versionId, String id, ElementDescriptionRequests.Update input) {
        return prepare(versionId, input, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        input,
                                        () -> {
                                            var request =
                                                    request(
                                                            scope,
                                                            input,
                                                            "update",
                                                            input.getClientRequestId(),
                                                            id,
                                                            input.getExpectedVersion(),
                                                            input);
                                            return receipts.replay(
                                                            request,
                                                            ignored ->
                                                                    find(scope, input, id)
                                                                            .map(
                                                                                    ElementDescriptionResponse
                                                                                            ::of))
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () ->
                                                                            require(
                                                                                            scope,
                                                                                            input,
                                                                                            id)
                                                                                    .flatMap(
                                                                                            row ->
                                                                                                    update(
                                                                                                            scope,
                                                                                                            input,
                                                                                                            row,
                                                                                                            request))));
                                        }))
                .checkpoint("prototype-hub/element-description/update");
    }

    private Mono<ElementDescriptionResponse> update(
            Scope scope,
            ElementDescriptionRequests.Update input,
            PrototypeElementDescriptionEntity row,
            PrototypeCreationReceiptService.Request request) {
        checkVersion(row, input.getExpectedVersion());
        if (row.getConfigVersion() == Long.MAX_VALUE) return Mono.error(conflict());
        long next = row.getConfigVersion() + 1;
        long now = System.currentTimeMillis();
        var update =
                descriptions
                        .createUpdate()
                        .set(PrototypeElementDescriptionEntity::getTitle, input.getTitle())
                        .set(
                                PrototypeElementDescriptionEntity::getPurpose,
                                text(input.getPurpose()))
                        .set(
                                PrototypeElementDescriptionEntity::getInputRules,
                                text(input.getInputRules()))
                        .set(
                                PrototypeElementDescriptionEntity::getPermissionRules,
                                text(input.getPermissionRules()))
                        .set(
                                PrototypeElementDescriptionEntity::getNavigationRules,
                                text(input.getNavigationRules()))
                        .set(PrototypeElementDescriptionEntity::getNotes, text(input.getNotes()))
                        .set(PrototypeElementDescriptionEntity::getConfigVersion, next)
                        .set(PrototypeElementDescriptionEntity::getUpdatedAt, now);
        // EasyORM的set忽略null；显式清空几何，避免响应已移除而数据库仍保留旧区域。
        if (input.getAnchor() == null) update.setNull(PrototypeElementDescriptionEntity::getAnchor);
        else update.set(PrototypeElementDescriptionEntity::getAnchor, input.getAnchor());
        return update.where(PrototypeElementDescriptionEntity::getId, row.getId())
                .and(
                        PrototypeElementDescriptionEntity::getConfigVersion,
                        input.getExpectedVersion())
                .execute()
                .flatMap(changed -> changed == 1 ? Mono.empty() : Mono.error(conflict()))
                .then(record(scope, input, request))
                .then(
                        Mono.fromSupplier(
                                () -> {
                                    copyText(input, row);
                                    row.setConfigVersion(next);
                                    row.setUpdatedAt(now);
                                    return ElementDescriptionResponse.of(row);
                                }));
    }

    public Mono<Boolean> delete(
            String versionId, String id, ElementDescriptionRequests.Delete input) {
        return prepare(versionId, input, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        input,
                                        () -> {
                                            var request =
                                                    request(
                                                            scope,
                                                            input,
                                                            "delete",
                                                            input.getClientRequestId(),
                                                            id,
                                                            input.getExpectedVersion(),
                                                            null);
                                            // 授权和固定范围重验后再重放true；删除后的回执一直保留到版本永久清理。
                                            return receipts.replay(
                                                            request, ignored -> Mono.just(true))
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () ->
                                                                            require(
                                                                                            scope,
                                                                                            input,
                                                                                            id)
                                                                                    .flatMap(
                                                                                            row -> {
                                                                                                checkVersion(
                                                                                                        row,
                                                                                                        input
                                                                                                                .getExpectedVersion());
                                                                                                return descriptions
                                                                                                        .createDelete()
                                                                                                        .where(
                                                                                                                PrototypeElementDescriptionEntity
                                                                                                                        ::getId,
                                                                                                                id)
                                                                                                        .and(
                                                                                                                PrototypeElementDescriptionEntity
                                                                                                                        ::getConfigVersion,
                                                                                                                input
                                                                                                                        .getExpectedVersion())
                                                                                                        .execute()
                                                                                                        .flatMap(
                                                                                                                changed ->
                                                                                                                        changed
                                                                                                                                        == 1
                                                                                                                                ? record(
                                                                                                                                                scope,
                                                                                                                                                input,
                                                                                                                                                request)
                                                                                                                                        .thenReturn(
                                                                                                                                                true)
                                                                                                                                : Mono
                                                                                                                                        .error(
                                                                                                                                                conflict()));
                                                                                            })));
                                        }))
                .checkpoint("prototype-hub/element-description/delete");
    }

    private Mono<Scope> prepare(
            String versionId, ElementDescriptionRequests.Scope input, Action action) {
        return access.current()
                .flatMap(
                        actor ->
                                access.requireVersion(actor, versionId, action, true)
                                        .flatMap(
                                                version ->
                                                        revisions
                                                                .require(
                                                                        version,
                                                                        input.getRevisionId())
                                                                .flatMap(revisions::pages)
                                                                .filter(
                                                                        pages ->
                                                                                contains(
                                                                                        pages,
                                                                                        input
                                                                                                .getPageId()))
                                                                .switchIfEmpty(
                                                                        Mono.error(
                                                                                () ->
                                                                                        PrototypeHubException
                                                                                                .missing(
                                                                                                        "page")))
                                                                .thenReturn(
                                                                        new Scope(
                                                                                actor, version))));
    }

    private <T> Mono<T> locked(
            Scope scope, ElementDescriptionRequests.Scope input, Supplier<Mono<T>> work) {
        // 锁外FileWork读取清单；锁内只重验不可变归属和当前授权，不持锁做文件I/O。
        return access.lockActiveUsers(scope.actor().userId())
                .then(access.lockProject(scope.version().getProjectId()))
                .then(access.lockVersion(scope.version().getId()))
                .then(
                        access.requireVersion(
                                scope.actor(), scope.version().getId(), Action.EDIT, false))
                .flatMap(version -> revisions.require(version, input.getRevisionId()))
                .then(Mono.defer(work))
                .as(transactions::transactional);
    }

    private Mono<PrototypeElementDescriptionEntity> find(
            Scope scope, ElementDescriptionRequests.Scope input, String id) {
        return descriptions
                .createQuery()
                .where(PrototypeElementDescriptionEntity::getId, id)
                .and(
                        PrototypeElementDescriptionEntity::getProjectId,
                        scope.version().getProjectId())
                .and(PrototypeElementDescriptionEntity::getVersionId, scope.version().getId())
                .and(PrototypeElementDescriptionEntity::getRevisionId, input.getRevisionId())
                .and(PrototypeElementDescriptionEntity::getPageId, input.getPageId())
                .fetch()
                .singleOrEmpty();
    }

    private Mono<PrototypeElementDescriptionEntity> require(
            Scope scope, ElementDescriptionRequests.Scope input, String id) {
        return find(scope, input, id)
                .switchIfEmpty(
                        Mono.error(() -> PrototypeHubException.missing("element_description")));
    }

    private PrototypeCreationReceiptService.Request request(
            Scope scope,
            ElementDescriptionRequests.Scope input,
            String action,
            String key,
            String id,
            Long expectedVersion,
            ElementDescriptionRequests.Create body) {
        return receipts.request(
                scope.actor().userId(),
                "element-description-" + action,
                input.getRevisionId() + ":" + input.getPageId(),
                key,
                scope.version().getProjectId(),
                scope.version().getId(),
                input.getRevisionId(),
                input.getPageId(),
                id,
                expectedVersion == null ? null : expectedVersion.toString(),
                body == null ? null : body.getTitle(),
                body == null ? null : text(body.getPurpose()),
                body == null ? null : text(body.getInputRules()),
                body == null ? null : text(body.getPermissionRules()),
                body == null ? null : text(body.getNavigationRules()),
                body == null ? null : text(body.getNotes()),
                body == null || body.getAnchor() == null ? null : body.getAnchor().canonical());
    }

    private Mono<Void> record(
            Scope scope,
            ElementDescriptionRequests.Scope input,
            PrototypeCreationReceiptService.Request request) {
        return receipts.record(request, scope.version().getId(), input.getRevisionId());
    }

    private static void copyText(
            ElementDescriptionRequests.Create input, PrototypeElementDescriptionEntity row) {
        row.setTitle(input.getTitle());
        row.setPurpose(text(input.getPurpose()));
        row.setInputRules(text(input.getInputRules()));
        row.setPermissionRules(text(input.getPermissionRules()));
        row.setNavigationRules(text(input.getNavigationRules()));
        row.setNotes(text(input.getNotes()));
        row.setAnchor(input.getAnchor());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static void checkVersion(PrototypeElementDescriptionEntity row, long expected) {
        if (row.getConfigVersion() != expected) throw conflict();
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
                "error.prototype-hub.element_description_conflict",
                "PROTOTYPE_HUB_ELEMENT_DESCRIPTION_CONFLICT",
                409);
    }

    private record Scope(Actor actor, PrototypeVersionEntity version) {}
}
