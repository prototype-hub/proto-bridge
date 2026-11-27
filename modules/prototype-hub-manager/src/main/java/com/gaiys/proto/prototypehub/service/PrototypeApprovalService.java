package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionApprovalEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.web.request.ApprovalRequests;
import com.gaiys.proto.prototypehub.web.response.ApprovalResponse;
import java.util.HashSet;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 正式批准和发布的只追加事务边界；浏览、线程解决和人工交付状态均不触发此服务。 沿现有HTTP/SQL追踪，仅加稳定checkpoint，不记录备注/姓名；无文件复制、通知或常驻状态/MBean。
 */
@Service
@RequiredArgsConstructor
public class PrototypeApprovalService {
    private static final long MAX_SAFE_VERSION = 9007199254740991L;
    private final ReactiveRepository<PrototypeRevisionApprovalEntity, String> decisions;
    private final PrototypeProjectAccessService access;
    private final PrototypeRevisionService revisions;
    private final PrototypeCreationReceiptService receipts;
    private final PrototypeShareService shares;
    private final TransactionalOperator transactions;

    public Mono<ApprovalResponse> detail(String version, String revision) {
        return prepare(version, revision, Action.READ).flatMap(this::current);
    }

    public Mono<PagerResult<ApprovalResponse.History>> history(
            String version, String revision, ApprovalRequests.History input) {
        return prepare(version, revision, Action.READ)
                .flatMap(
                        scope -> {
                            var query =
                                    QueryParamEntity.newQuery()
                                            .where(
                                                    PrototypeRevisionApprovalEntity::getProjectId,
                                                    scope.revision().getProjectId())
                                            .and(
                                                    PrototypeRevisionApprovalEntity::getVersionId,
                                                    version)
                                            .and(
                                                    PrototypeRevisionApprovalEntity::getRevisionId,
                                                    revision)
                                            .orderByDesc(
                                                    PrototypeRevisionApprovalEntity
                                                            ::getConfigVersion)
                                            .getParam();
                            query.doPaging(input.getPageIndex(), input.getPageSize());
                            query.setParallelPager(true);
                            // 固定范围先进入COUNT与分页，不能取整段历史后在内存过滤。
                            return QueryHelper.queryPager(
                                    query, decisions::createQuery, ApprovalResponse.History::of);
                        });
    }

    public Mono<ApprovalResponse> act(String version, String revision, ApprovalRequests.Act input) {
        return prepare(version, revision, Action.EDIT)
                .flatMap(
                        scope ->
                                access.lockActiveUsers(scope.actor().userId())
                                        .then(access.lockProject(scope.revision().getProjectId()))
                                        .then(access.lockVersion(version))
                                        .then(access.actor(scope.actor().userId()))
                                        .flatMap(
                                                actor ->
                                                        authorize(
                                                                actor,
                                                                version,
                                                                revision,
                                                                Action.EDIT,
                                                                false))
                                        .flatMap(locked -> apply(locked, input))
                                        .as(transactions::transactional))
                .checkpoint("prototype-hub/approval/act");
    }

    /** 只复用分享读取授权，故关闭评论或未创建访客身份也可读取；凭据不进入URL或日志。 */
    public Mono<ApprovalResponse.PublicContext> publicContext(
            String token, ApprovalRequests.PublicContext input) {
        return shares.requireRevisionAccess(token, input.getAccessKey(), input.getRevisionId())
                .flatMap(revision -> current(new Scope(null, revision)))
                .map(
                        state ->
                                new ApprovalResponse.PublicContext(
                                        state.revisionId(),
                                        state.approved(),
                                        state.published(),
                                        state.approval() == null
                                                ? null
                                                : state.approval().createTime(),
                                        state.publication() == null
                                                ? null
                                                : state.publication().createTime()));
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
                .flatMap(current -> revisions.require(current, revision))
                .map(fixed -> new Scope(actor, fixed));
    }

    private Mono<ApprovalResponse> apply(Scope scope, ApprovalRequests.Act input) {
        String note = input.getNote() == null ? "" : input.getNote();
        var fixed = scope.revision();
        var request =
                receipts.request(
                        scope.actor().userId(),
                        "revision-approval",
                        fixed.getId(),
                        input.getClientRequestId(),
                        fixed.getProjectId(),
                        fixed.getVersionId(),
                        fixed.getId(),
                        input.getAction(),
                        note,
                        input.getExpectedVersion().toString());
        // 回执重放必须读当前状态，不能让旧publish在撤下或撤销后重新看似有效。
        return receipts.replay(request, ignored -> current(scope))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        latest(scope)
                                                .flatMap(
                                                        previous -> {
                                                            if (previous.getConfigVersion()
                                                                                    .longValue()
                                                                            != input.getExpectedVersion()
                                                                                    .longValue()
                                                                    || previous.getConfigVersion()
                                                                            >= MAX_SAFE_VERSION)
                                                                return Mono.error(conflict());
                                                            var result =
                                                                    transition(
                                                                            previous,
                                                                            input.getAction(),
                                                                            request.id());
                                                            var row =
                                                                    new PrototypeRevisionApprovalEntity();
                                                            row.setId(request.id());
                                                            row.setProjectId(fixed.getProjectId());
                                                            row.setVersionId(fixed.getVersionId());
                                                            row.setRevisionId(fixed.getId());
                                                            row.setConfigVersion(
                                                                    previous.getConfigVersion()
                                                                            + 1);
                                                            row.setAction(input.getAction());
                                                            row.setApproved(result.approved());
                                                            row.setPublished(result.published());
                                                            row.setApprovalId(result.approvalId());
                                                            row.setPublicationId(
                                                                    result.publicationId());
                                                            row.setActorId(scope.actor().userId());
                                                            row.setActorName(
                                                                    scope.actor()
                                                                            .authentication()
                                                                            .getUser()
                                                                            .getName());
                                                            row.setNote(note);
                                                            row.setCreateTime(
                                                                    System.currentTimeMillis());
                                                            return decisions
                                                                    .insert(row)
                                                                    .then(
                                                                            receipts.record(
                                                                                    request,
                                                                                    fixed
                                                                                            .getVersionId(),
                                                                                    fixed.getId()))
                                                                    .then(current(scope));
                                                        })));
    }

    /** 状态机只接受显式动作；撤销批准同时失效当前发布，原发布行不改写。 */
    static Result transition(PrototypeRevisionApprovalEntity previous, String action, String id) {
        boolean approved = Boolean.TRUE.equals(previous.getApproved());
        boolean published = Boolean.TRUE.equals(previous.getPublished());
        return switch (action) {
            case "approve" -> {
                if (approved) throw conflict();
                yield new Result(true, false, id, null);
            }
            case "revoke" -> {
                if (!approved) throw conflict();
                yield new Result(false, false, null, null);
            }
            case "publish" -> {
                if (!approved || published) throw conflict();
                yield new Result(true, true, previous.getApprovalId(), id);
            }
            case "withdraw" -> {
                if (!published) throw conflict();
                yield new Result(true, false, previous.getApprovalId(), null);
            }
            default -> throw conflict();
        };
    }

    private Mono<PrototypeRevisionApprovalEntity> latest(Scope scope) {
        var fixed = scope.revision();
        return decisions
                .createQuery()
                .where(PrototypeRevisionApprovalEntity::getProjectId, fixed.getProjectId())
                .and(PrototypeRevisionApprovalEntity::getVersionId, fixed.getVersionId())
                .and(PrototypeRevisionApprovalEntity::getRevisionId, fixed.getId())
                .orderBy(SortOrder.desc(PrototypeRevisionApprovalEntity::getConfigVersion))
                .paging(0, 1)
                .fetch()
                .singleOrEmpty()
                .defaultIfEmpty(initial(fixed));
    }

    private Mono<ApprovalResponse> current(Scope scope) {
        return latest(scope)
                .flatMap(
                        row ->
                                pointers(scope, row)
                                        .map(
                                                pointers ->
                                                        new ApprovalResponse(
                                                                row.getProjectId(),
                                                                row.getVersionId(),
                                                                row.getRevisionId(),
                                                                row.getConfigVersion(),
                                                                row.getApproved(),
                                                                row.getPublished(),
                                                                pointer(
                                                                        pointers,
                                                                        row.getApprovalId()),
                                                                pointer(
                                                                        pointers,
                                                                        row.getPublicationId()),
                                                                ApprovalResponse.Revision.of(
                                                                        scope.revision()))));
    }

    private Mono<Map<String, PrototypeRevisionApprovalEntity>> pointers(
            Scope scope, PrototypeRevisionApprovalEntity row) {
        var ids = new HashSet<String>();
        if (row.getApprovalId() != null) ids.add(row.getApprovalId());
        if (row.getPublicationId() != null) ids.add(row.getPublicationId());
        if (ids.isEmpty()) return Mono.just(Map.of());
        // 最多两个同范围指针，不跟随其他项目/修订的历史或加载完整历史。
        var fixed = scope.revision();
        return decisions
                .createQuery()
                .where(PrototypeRevisionApprovalEntity::getProjectId, fixed.getProjectId())
                .and(PrototypeRevisionApprovalEntity::getVersionId, fixed.getVersionId())
                .and(PrototypeRevisionApprovalEntity::getRevisionId, fixed.getId())
                .in(PrototypeRevisionApprovalEntity::getId, ids)
                .fetch()
                .collectMap(PrototypeRevisionApprovalEntity::getId);
    }

    private static ApprovalResponse.Decision pointer(
            Map<String, PrototypeRevisionApprovalEntity> rows, String id) {
        return id == null ? null : ApprovalResponse.Decision.of(rows.get(id));
    }

    private static PrototypeRevisionApprovalEntity initial(PrototypeRevisionEntity fixed) {
        var row = new PrototypeRevisionApprovalEntity();
        row.setProjectId(fixed.getProjectId());
        row.setVersionId(fixed.getVersionId());
        row.setRevisionId(fixed.getId());
        row.setConfigVersion(0L);
        row.setApproved(false);
        row.setPublished(false);
        return row;
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.approval_conflict", "PROTOTYPE_HUB_APPROVAL_CONFLICT", 409);
    }

    record Result(boolean approved, boolean published, String approvalId, String publicationId) {}

    private record Scope(Actor actor, PrototypeRevisionEntity revision) {}
}
