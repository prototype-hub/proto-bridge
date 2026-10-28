package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeWorkItemEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.WorkItemCreateRequest;
import com.gaiys.proto.prototypehub.web.request.WorkItemDeleteRequest;
import com.gaiys.proto.prototypehub.web.request.WorkItemPageRequest;
import com.gaiys.proto.prototypehub.web.request.WorkItemQueryRequest;
import com.gaiys.proto.prototypehub.web.request.WorkItemUpdateRequest;
import com.gaiys.proto.prototypehub.web.response.WorkItemResponse;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** 固定页面的人工工作项引用；不抓取URL、不发业务通知。写入沿账号→项目→版本锁序， 数据与既有持久回执同事务。复用HTTP/SQL追踪，不记录外链/备注，无常驻状态或新MBean。 */
@Service
@RequiredArgsConstructor
public class PrototypeWorkItemService {
    private final ReactiveRepository<PrototypeWorkItemEntity, String> items;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeCreationReceiptService receipts;
    private final TransactionalOperator transactions;

    public Mono<PagerResult<WorkItemResponse>> query(String versionId, WorkItemQueryRequest input) {
        return prepare(versionId, input, Action.READ)
                .flatMap(
                        scope -> {
                            QueryParamEntity query =
                                    QueryParamEntity.newQuery()
                                            .where(
                                                    PrototypeWorkItemEntity::getProjectId,
                                                    scope.version().getProjectId())
                                            .and(
                                                    PrototypeWorkItemEntity::getVersionId,
                                                    scope.version().getId())
                                            .and(
                                                    PrototypeWorkItemEntity::getRevisionId,
                                                    input.getRevisionId())
                                            .and(
                                                    PrototypeWorkItemEntity::getPageId,
                                                    input.getPageId())
                                            .orderByDesc(PrototypeWorkItemEntity::getCreatedAt)
                                            .orderByAsc(PrototypeWorkItemEntity::getId)
                                            .getParam();
                            query.doPaging(input.getPageIndex(), input.getPageSize());
                            query.setParallelPager(true);
                            return QueryHelper.queryPager(
                                    query, items::createQuery, WorkItemResponse::of);
                        });
    }

    public Mono<WorkItemResponse> create(String versionId, WorkItemCreateRequest input) {
        return prepare(versionId, input, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        input,
                                        ignored -> {
                                            String url = validatedUrl(input.getUrl());
                                            String note = note(input);
                                            var request =
                                                    request(
                                                            scope,
                                                            input,
                                                            "create",
                                                            input.getClientRequestId(),
                                                            null,
                                                            null,
                                                            url,
                                                            note);
                                            // 创建标识由已散列的幂等请求身份确定；重试不新建，已删引用不复活。
                                            return receipts.replay(
                                                            request,
                                                            id ->
                                                                    find(scope, input, request.id())
                                                                            .map(
                                                                                    WorkItemResponse
                                                                                            ::of))
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () -> {
                                                                        long now =
                                                                                System
                                                                                        .currentTimeMillis();
                                                                        var row =
                                                                                new PrototypeWorkItemEntity();
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
                                                                        row.setUrl(url);
                                                                        row.setNote(note);
                                                                        row.setConfigVersion(0L);
                                                                        row.setCreatedAt(now);
                                                                        row.setUpdatedAt(now);
                                                                        return items.insert(row)
                                                                                .then(
                                                                                        record(
                                                                                                scope,
                                                                                                input,
                                                                                                request))
                                                                                .thenReturn(
                                                                                        WorkItemResponse
                                                                                                .of(
                                                                                                        row));
                                                                    }));
                                        }))
                .checkpoint("prototype-hub/work-item/create");
    }

    public Mono<WorkItemResponse> update(String versionId, String id, WorkItemUpdateRequest input) {
        return prepare(versionId, input, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        input,
                                        ignored -> {
                                            String url = validatedUrl(input.getUrl());
                                            String note = note(input);
                                            var request =
                                                    request(
                                                            scope,
                                                            input,
                                                            "update",
                                                            input.getClientRequestId(),
                                                            id,
                                                            input.getExpectedVersion(),
                                                            url,
                                                            note);
                                            return receipts.replay(
                                                            request,
                                                            resource ->
                                                                    find(scope, input, id)
                                                                            .map(
                                                                                    WorkItemResponse
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
                                                                                                            url,
                                                                                                            note,
                                                                                                            request))));
                                        }))
                .checkpoint("prototype-hub/work-item/update");
    }

    public Mono<Boolean> delete(String versionId, String id, WorkItemDeleteRequest input) {
        return prepare(versionId, input, Action.EDIT)
                .flatMap(
                        scope ->
                                locked(
                                        scope,
                                        input,
                                        ignored -> {
                                            var request =
                                                    request(
                                                            scope,
                                                            input,
                                                            "delete",
                                                            input.getClientRequestId(),
                                                            id,
                                                            input.getExpectedVersion(),
                                                            null,
                                                            null);
                                            // 先重验当前授权，再读取删除回执；成功重放不因引用行已删除而404。
                                            return receipts.replay(
                                                            request, resource -> Mono.just(true))
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
                                                                                                return items.createDelete()
                                                                                                        .where(
                                                                                                                PrototypeWorkItemEntity
                                                                                                                        ::getId,
                                                                                                                id)
                                                                                                        .and(
                                                                                                                PrototypeWorkItemEntity
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
                .checkpoint("prototype-hub/work-item/delete");
    }

    private Mono<WorkItemResponse> update(
            Scope scope,
            WorkItemUpdateRequest input,
            PrototypeWorkItemEntity row,
            String url,
            String note,
            PrototypeCreationReceiptService.Request request) {
        checkVersion(row, input.getExpectedVersion());
        if (row.getConfigVersion() == Long.MAX_VALUE) return Mono.error(conflict());
        long next = row.getConfigVersion() + 1;
        long now = System.currentTimeMillis();
        return items.createUpdate()
                .set(PrototypeWorkItemEntity::getUrl, url)
                .set(PrototypeWorkItemEntity::getNote, note)
                .set(PrototypeWorkItemEntity::getConfigVersion, next)
                .set(PrototypeWorkItemEntity::getUpdatedAt, now)
                .where(PrototypeWorkItemEntity::getId, row.getId())
                .and(PrototypeWorkItemEntity::getConfigVersion, input.getExpectedVersion())
                .execute()
                .flatMap(changed -> changed == 1 ? Mono.empty() : Mono.error(conflict()))
                .then(record(scope, input, request))
                .then(
                        Mono.fromSupplier(
                                () -> {
                                    row.setUrl(url);
                                    row.setNote(note);
                                    row.setConfigVersion(next);
                                    row.setUpdatedAt(now);
                                    return WorkItemResponse.of(row);
                                }));
    }

    private Mono<Scope> prepare(String versionId, WorkItemPageRequest input, Action action) {
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
            Scope scope, WorkItemPageRequest input, Function<Scope, Mono<T>> work) {
        // 页面清单已在锁外FileWork解析。锁内只重验不变修订归属与当前授权，避免持锁做文件I/O。
        return access.lockActiveUsers(scope.actor().userId())
                .then(access.lockProject(scope.version().getProjectId()))
                .then(access.lockVersion(scope.version().getId()))
                .then(
                        access.requireVersion(
                                scope.actor(), scope.version().getId(), Action.EDIT, false))
                .flatMap(version -> revisions.require(version, input.getRevisionId()))
                .then(Mono.defer(() -> work.apply(scope)))
                .as(transactions::transactional);
    }

    private Mono<PrototypeWorkItemEntity> find(Scope scope, WorkItemPageRequest input, String id) {
        return items.createQuery()
                .where(PrototypeWorkItemEntity::getId, id)
                .and(PrototypeWorkItemEntity::getProjectId, scope.version().getProjectId())
                .and(PrototypeWorkItemEntity::getVersionId, scope.version().getId())
                .and(PrototypeWorkItemEntity::getRevisionId, input.getRevisionId())
                .and(PrototypeWorkItemEntity::getPageId, input.getPageId())
                .fetch()
                .singleOrEmpty();
    }

    private Mono<PrototypeWorkItemEntity> require(
            Scope scope, WorkItemPageRequest input, String id) {
        return find(scope, input, id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("work_item")));
    }

    private PrototypeCreationReceiptService.Request request(
            Scope scope,
            WorkItemPageRequest input,
            String action,
            String key,
            String id,
            Long expectedVersion,
            String url,
            String note) {
        return receipts.request(
                scope.actor().userId(),
                "work-item-" + action,
                input.getRevisionId() + ":" + input.getPageId(),
                key,
                scope.version().getProjectId(),
                scope.version().getId(),
                input.getRevisionId(),
                input.getPageId(),
                id,
                expectedVersion == null ? null : expectedVersion.toString(),
                url,
                note);
    }

    private Mono<Void> record(
            Scope scope,
            WorkItemPageRequest input,
            PrototypeCreationReceiptService.Request request) {
        // 回执归逻辑版本，复用版本永久清理路径一并删除，不遗留孤立去重数据。
        return receipts.record(request, scope.version().getId(), input.getRevisionId());
    }

    private static void checkVersion(PrototypeWorkItemEntity row, long expected) {
        if (row.getConfigVersion() != expected) throw conflict();
    }

    static String validatedUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535
                    || value.codePoints()
                            .anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c)))
                throw invalidUrl();
            return value;
        } catch (IllegalArgumentException error) {
            throw invalidUrl();
        }
    }

    private static String note(WorkItemCreateRequest input) {
        return input.getNote() == null ? "" : input.getNote();
    }

    private static boolean contains(List<PrototypePageInfo> pages, String id) {
        return pages.stream()
                .anyMatch(
                        page ->
                                (!page.folder() && id.equals(page.pageId()))
                                        || contains(page.children(), id));
    }

    private static PrototypeHubException invalidUrl() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.work_item_url_invalid", "PROTOTYPE_HUB_WORK_ITEM_URL_INVALID");
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.work_item_conflict", "PROTOTYPE_HUB_WORK_ITEM_CONFLICT", 409);
    }

    private record Scope(Actor actor, PrototypeVersionEntity version) {}
}
