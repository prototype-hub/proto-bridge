package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.configuration.builder.PrototypeTextTermFragmentBuilder;
import com.gaiys.proto.prototypehub.entity.PrototypePageDeliveryEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.PageDeliveryRequests;
import com.gaiys.proto.prototypehub.web.response.PageDeliveryResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewAssigneeResponse;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 人工交付状态独立于上传、正式批准和通知。固定页在锁外FileWork解析，写入按用户→项目→版本锁序并与回执同事务提交。
 * 复用现有CE项目数据权限和HTTP/SQL追踪，不记录理由/姓名，无常驻状态或新MBean。
 */
@Service
@RequiredArgsConstructor
public class PrototypePageDeliveryService {
    private final ReactiveRepository<PrototypePageDeliveryEntity, String> deliveries;
    private final ReactiveRepository<PrototypeProjectEntity, String> projects;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeCreationReceiptService receipts;
    private final QueryHelper queries;
    private final TransactionalOperator transactions;

    public Mono<PageDeliveryResponse> detail(
            String version, String page, PageDeliveryRequests.Scope input) {
        return preparePage(version, input.getRevisionId(), page, Action.READ)
                .flatMap(
                        scope ->
                                find(scope)
                                        .defaultIfEmpty(unset(scope))
                                        .flatMap(row -> response(scope, row)));
    }

    public Mono<PagerResult<PageDeliveryResponse>> query(
            String version, PageDeliveryRequests.Query input) {
        return prepareRevision(version, input.getRevisionId(), Action.READ)
                .flatMap(
                        scope -> {
                            var builder =
                                    QueryParamEntity.newQuery()
                                            .where(
                                                    PrototypePageDeliveryEntity::getProjectId,
                                                    scope.version().getProjectId())
                                            .and(PrototypePageDeliveryEntity::getVersionId, version)
                                            .and(
                                                    PrototypePageDeliveryEntity::getRevisionId,
                                                    input.getRevisionId());
                            if (input.getState() != null)
                                builder.and(
                                        PrototypePageDeliveryEntity::getState, input.getState());
                            var query =
                                    builder.orderByDesc(PrototypePageDeliveryEntity::getUpdatedAt)
                                            .orderByAsc(PrototypePageDeliveryEntity::getPageId)
                                            .getParam();
                            query.doPaging(input.getPageIndex(), input.getPageSize());
                            query.setParallelPager(true);
                            // 权限/范围/状态先于COUNT和分页；只查已设置行，不构建整包页面关系表。
                            return QueryHelper.transformPageResult(
                                    QueryHelper.queryPager(
                                            query, deliveries::createQuery, row -> row),
                                    rows -> responses(scope, rows));
                        });
    }

    public Mono<PagerResult<ReviewAssigneeResponse>> candidates(
            String version, PageDeliveryRequests.Candidates input) {
        return prepareRevision(version, input.getRevisionId(), Action.EDIT)
                .flatMap(
                        scope ->
                                owner(scope.version().getProjectId())
                                        .flatMap(
                                                owner -> {
                                                    var page = new QueryParamEntity();
                                                    page.doPaging(
                                                            input.getPageIndex(),
                                                            input.getPageSize());
                                                    page.setParallelPager(true);
                                                    var query =
                                                            eligible(
                                                                    scope.version().getProjectId(),
                                                                    owner,
                                                                    page);
                                                    if (input.getKeyword() != null
                                                            && !input.getKeyword().isEmpty())
                                                        query.where(
                                                                condition ->
                                                                        condition.accept(
                                                                                UserEntity::getName,
                                                                                PrototypeTextTermFragmentBuilder
                                                                                        .LITERAL_CONTAINS
                                                                                        .getTermType(),
                                                                                input
                                                                                        .getKeyword()));
                                                    return query.orderByAsc(UserEntity::getName)
                                                            .orderByAsc(UserEntity::getId)
                                                            .fetchPaged(
                                                                    rows ->
                                                                            Mono.just(
                                                                                    rows.stream()
                                                                                            .map(
                                                                                                    row ->
                                                                                                            new ReviewAssigneeResponse(
                                                                                                                    row
                                                                                                                            .getUserId(),
                                                                                                                    row
                                                                                                                            .getName()))
                                                                                            .toList()));
                                                }));
    }

    public Mono<PageDeliveryResponse> save(
            String version, String page, PageDeliveryRequests.Save input) {
        return preparePage(version, input.getRevisionId(), page, Action.EDIT)
                .flatMap(
                        scope ->
                                lockUsers(scope.actor().userId(), input.getAssigneeId())
                                        .flatMap(
                                                targetActive ->
                                                        access.lockProject(
                                                                        scope.version()
                                                                                .getProjectId())
                                                                .then(access.lockVersion(version))
                                                                .then(
                                                                        access.requireVersion(
                                                                                scope.actor(),
                                                                                version,
                                                                                Action.EDIT,
                                                                                false))
                                                                .flatMap(
                                                                        current ->
                                                                                revisions.require(
                                                                                        current,
                                                                                        input
                                                                                                .getRevisionId()))
                                                                .then(
                                                                        Mono.defer(
                                                                                () ->
                                                                                        saveLocked(
                                                                                                scope,
                                                                                                input,
                                                                                                targetActive))))
                                        .as(transactions::transactional))
                .checkpoint("prototype-hub/page-delivery/save");
    }

    private Mono<PageDeliveryResponse> saveLocked(
            Scope scope, PageDeliveryRequests.Save input, boolean targetActive) {
        String reason = input.getReason() == null ? "" : input.getReason();
        var request =
                receipts.request(
                        scope.actor().userId(),
                        "page-delivery-save",
                        deliveryId(scope),
                        input.getClientRequestId(),
                        scope.version().getProjectId(),
                        scope.version().getId(),
                        scope.revision().getId(),
                        scope.page().pageId(),
                        input.getState(),
                        input.getAssigneeId(),
                        reason,
                        input.getExpectedVersion().toString());
        // 先重验调用者与固定范围，再重放；已提交键不复活旧值，也不因旧负责人失效而失去恢复能力。
        return receipts.replay(request, ignored -> find(scope).flatMap(row -> response(scope, row)))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        find(scope)
                                                .defaultIfEmpty(unset(scope))
                                                .flatMap(
                                                        row ->
                                                                persist(
                                                                        scope,
                                                                        row,
                                                                        input,
                                                                        reason,
                                                                        targetActive,
                                                                        request))));
    }

    private Mono<PageDeliveryResponse> persist(
            Scope scope,
            PrototypePageDeliveryEntity row,
            PageDeliveryRequests.Save input,
            String reason,
            boolean targetActive,
            PrototypeCreationReceiptService.Request request) {
        if (row.getConfigVersion().longValue() != input.getExpectedVersion().longValue()
                || row.getConfigVersion() == Long.MAX_VALUE) return Mono.error(conflict());
        Mono<String> assigneeName;
        if (input.getAssigneeId() == null) assigneeName = Mono.just("");
        else if (!targetActive) assigneeName = Mono.error(invalidAssignee());
        else
            assigneeName =
                    owner(scope.version().getProjectId())
                            .flatMap(
                                    owner ->
                                            eligible(scope.version().getProjectId(), owner, null)
                                                    .where(
                                                            condition ->
                                                                    condition.is(
                                                                            UserEntity::getId,
                                                                            input.getAssigneeId()))
                                                    .fetch()
                                                    .singleOrEmpty()
                                                    .map(AssigneeRow::getName))
                            .switchIfEmpty(
                                    Mono.error(PrototypePageDeliveryService::invalidAssignee));
        return assigneeName.flatMap(
                name -> {
                    long previous = row.getConfigVersion();
                    row.setState(input.getState());
                    row.setAssigneeId(input.getAssigneeId());
                    row.setAssigneeName(input.getAssigneeId() == null ? null : name);
                    row.setReason(reason);
                    row.setConfigVersion(previous + 1);
                    row.setUpdatedAt(System.currentTimeMillis());
                    Mono<Void> write;
                    if (previous == 0) write = deliveries.insert(row).then();
                    else {
                        var update =
                                deliveries
                                        .createUpdate()
                                        .set(PrototypePageDeliveryEntity::getState, row.getState())
                                        .set(
                                                PrototypePageDeliveryEntity::getReason,
                                                row.getReason())
                                        .set(
                                                PrototypePageDeliveryEntity::getConfigVersion,
                                                row.getConfigVersion())
                                        .set(
                                                PrototypePageDeliveryEntity::getUpdatedAt,
                                                row.getUpdatedAt());
                        if (row.getAssigneeId() == null)
                            update.setNull(PrototypePageDeliveryEntity::getAssigneeId)
                                    .setNull(PrototypePageDeliveryEntity::getAssigneeName);
                        else
                            update.set(
                                            PrototypePageDeliveryEntity::getAssigneeId,
                                            row.getAssigneeId())
                                    .set(
                                            PrototypePageDeliveryEntity::getAssigneeName,
                                            row.getAssigneeName());
                        write =
                                update.where(PrototypePageDeliveryEntity::getId, row.getId())
                                        .and(
                                                PrototypePageDeliveryEntity::getConfigVersion,
                                                previous)
                                        .execute()
                                        .flatMap(
                                                changed ->
                                                        changed == 1
                                                                ? Mono.empty()
                                                                : Mono.error(conflict()));
                    }
                    // 回执归版本，随其永久清理；不保存原始请求键、理由或姓名。
                    return write.then(
                                    receipts.record(
                                            request,
                                            scope.version().getId(),
                                            scope.revision().getId()))
                            .thenReturn(
                                    PageDeliveryResponse.of(row, input.getAssigneeId() != null));
                });
    }

    private Mono<Scope> prepareRevision(String version, String revision, Action action) {
        return access.current()
                .flatMap(
                        actor ->
                                access.requireVersion(actor, version, action, true)
                                        .flatMap(
                                                current ->
                                                        revisions
                                                                .require(current, revision)
                                                                .map(
                                                                        fixed ->
                                                                                new Scope(
                                                                                        actor,
                                                                                        current,
                                                                                        fixed,
                                                                                        null))));
    }

    private Mono<Scope> preparePage(String version, String revision, String page, Action action) {
        return prepareRevision(version, revision, action)
                .flatMap(
                        scope ->
                                revisions
                                        .pages(scope.revision())
                                        .flatMap(pages -> Mono.justOrEmpty(findPage(pages, page)))
                                        .switchIfEmpty(
                                                Mono.error(
                                                        () ->
                                                                PrototypeHubException.missing(
                                                                        "page")))
                                        .map(
                                                fixed ->
                                                        new Scope(
                                                                scope.actor(),
                                                                scope.version(),
                                                                scope.revision(),
                                                                fixed)));
    }

    private Mono<Boolean> lockUsers(String actor, String target) {
        // 同时锁调用者/目标，稳定用户顺序先于项目锁；旧目标失效仍允许已提交请求重放。
        return Flux.fromStream(
                        () -> Stream.of(actor, target).filter(Objects::nonNull).distinct().sorted())
                .concatMap(
                        id ->
                                access.tryLockActiveUser(id)
                                        .map(active -> new LockedUser(id, active)),
                        1)
                .collectList()
                .flatMap(
                        rows -> {
                            if (rows.stream()
                                    .noneMatch(row -> actor.equals(row.id()) && row.active()))
                                return Mono.error(
                                        PrototypeHubException.forbidden(
                                                "error.prototype-hub.user_unavailable",
                                                "PROTOTYPE_HUB_USER_UNAVAILABLE"));
                            return Mono.just(
                                    target == null
                                            || rows.stream()
                                                    .anyMatch(
                                                            row ->
                                                                    target.equals(row.id())
                                                                            && row.active()));
                        });
    }

    private Mono<PrototypePageDeliveryEntity> find(Scope scope) {
        return deliveries
                .createQuery()
                .where(PrototypePageDeliveryEntity::getId, deliveryId(scope))
                .and(PrototypePageDeliveryEntity::getProjectId, scope.version().getProjectId())
                .and(PrototypePageDeliveryEntity::getVersionId, scope.version().getId())
                .and(PrototypePageDeliveryEntity::getRevisionId, scope.revision().getId())
                .and(PrototypePageDeliveryEntity::getPageId, scope.page().pageId())
                .fetch()
                .singleOrEmpty();
    }

    private String deliveryId(Scope scope) {
        return receipts.request(
                        "page-delivery", "record", scope.revision().getId(), scope.page().pageId())
                .id();
    }

    private PrototypePageDeliveryEntity unset(Scope scope) {
        var row = new PrototypePageDeliveryEntity();
        row.setId(deliveryId(scope));
        row.setProjectId(scope.version().getProjectId());
        row.setVersionId(scope.version().getId());
        row.setRevisionId(scope.revision().getId());
        row.setPageId(scope.page().pageId());
        row.setPageName(scope.page().name());
        row.setPagePath(scope.page().path());
        row.setReason("");
        row.setConfigVersion(0L);
        return row;
    }

    private Mono<PageDeliveryResponse> response(Scope scope, PrototypePageDeliveryEntity row) {
        return responses(scope, List.of(row)).map(List::getFirst);
    }

    private Mono<List<PageDeliveryResponse>> responses(
            Scope scope, List<PrototypePageDeliveryEntity> rows) {
        Set<String> ids =
                rows.stream()
                        .map(PrototypePageDeliveryEntity::getAssigneeId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        if (ids.isEmpty())
            return Mono.just(
                    rows.stream().map(row -> PageDeliveryResponse.of(row, false)).toList());
        // 一页最多五十行；资格批量查一次，不按负责人/页面逐条查询或解析清单。
        return owner(scope.version().getProjectId())
                .flatMap(
                        owner ->
                                eligible(scope.version().getProjectId(), owner, null)
                                        .where(condition -> condition.in(UserEntity::getId, ids))
                                        .fetch()
                                        .map(AssigneeRow::getUserId)
                                        .collect(Collectors.toSet()))
                .map(
                        available ->
                                rows.stream()
                                        .map(
                                                row ->
                                                        PageDeliveryResponse.of(
                                                                row,
                                                                available.contains(
                                                                        row.getAssigneeId())))
                                        .toList());
    }

    private Mono<String> owner(String projectId) {
        return projects.createQuery()
                .select(PrototypeProjectEntity::getOwnerId)
                .where(PrototypeProjectEntity::getId, projectId)
                .fetch()
                .singleOrEmpty()
                .map(row -> row.getOwnerId() == null ? "" : row.getOwnerId());
    }

    private QueryHelper.JoinSpec<AssigneeRow> eligible(
            String projectId, String owner, QueryParamEntity page) {
        var query =
                queries.select(AssigneeRow.class)
                        .as(UserEntity::getId, AssigneeRow::setUserId)
                        .as(UserEntity::getName, AssigneeRow::setName)
                        .from(UserEntity.class)
                        .leftJoin(
                                PrototypeProjectMemberEntity.class,
                                join ->
                                        join.is(
                                                        PrototypeProjectMemberEntity::getUserId,
                                                        UserEntity::getId)
                                                .and(
                                                        PrototypeProjectMemberEntity::getProjectId,
                                                        projectId)
                                                .in(
                                                        PrototypeProjectMemberEntity::getRole,
                                                        "editor",
                                                        "viewer"));
        if (page != null) query.where(page);
        // 成员唯一索引避免JOIN重复；资格和姓名字面筛选必须先于COUNT/数据库分页。
        query.where(
                condition ->
                        condition
                                .is(UserEntity::getStatus, (byte) 1)
                                .nest(
                                        nest -> {
                                            if (!owner.isBlank())
                                                nest.is(UserEntity::getId, owner).or();
                                            nest.notNull(PrototypeProjectMemberEntity::getId);
                                        }));
        return query;
    }

    static PrototypePageInfo findPage(List<PrototypePageInfo> pages, String id) {
        for (PrototypePageInfo page : pages) {
            if (!page.folder() && id.equals(page.pageId())) return page;
            PrototypePageInfo child = findPage(page.children(), id);
            if (child != null) return child;
        }
        return null;
    }

    static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.page_delivery_conflict",
                "PROTOTYPE_HUB_PAGE_DELIVERY_CONFLICT",
                409);
    }

    private static PrototypeHubException invalidAssignee() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.page_delivery_assignee_invalid",
                "PROTOTYPE_HUB_PAGE_DELIVERY_ASSIGNEE_INVALID");
    }

    /** 一次数据库查询投影的候选，不包含登录名或全局角色。 */
    @Getter
    @Setter
    public static class AssigneeRow {
        private String userId;
        private String name;
    }

    private record Scope(
            Actor actor,
            PrototypeVersionEntity version,
            PrototypeRevisionEntity revision,
            PrototypePageInfo page) {}

    private record LockedUser(String id, boolean active) {}
}
