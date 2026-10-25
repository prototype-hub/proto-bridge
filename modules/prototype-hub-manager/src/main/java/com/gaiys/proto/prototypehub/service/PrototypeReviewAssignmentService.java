package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.configuration.builder.PrototypeTextTermFragmentBuilder;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewEventEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewThreadEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import com.gaiys.proto.prototypehub.web.request.ReviewAssignmentCandidateRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewAssignmentRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewPageRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewAssigneeResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewAssignmentResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 线程的内部责任标记，不授予资产权限，也不修改公开线程元数据。 页面授权由线程服务统一提供；用户锁先于项目/版本锁，分派、事件与回执在同一事务提交。
 * 无独立缓存或调度器，复用HTTP/SQL追踪和持久化outbox，不记录姓名或原始请求键。
 */
@Service
@RequiredArgsConstructor
public class PrototypeReviewAssignmentService {
    private final PrototypeReviewThreadService threadAccess;
    private final PrototypeProjectAccessService access;
    private final ReactiveRepository<PrototypeProjectEntity, String> projects;
    private final ReactiveRepository<PrototypeReviewThreadEntity, String> threads;
    private final ReactiveRepository<PrototypeReviewEventEntity, String> events;
    private final PrototypeCreationReceiptService receipts;
    private final QueryHelper queries;
    private final TransactionalOperator transactions;

    public Mono<ReviewAssignmentResponse> detail(
            String versionId, String threadId, ReviewPageRequest input) {
        return threadAccess
                .currentThread(versionId, threadId, input)
                .flatMap(prepared -> response(prepared.thread()));
    }

    public Mono<PagerResult<ReviewAssigneeResponse>> candidates(
            String versionId, String threadId, ReviewAssignmentCandidateRequest input) {
        return threadAccess
                .currentThread(versionId, threadId, input)
                .flatMap(
                        prepared ->
                                owner(prepared.thread().getProjectId())
                                        .flatMap(
                                                owner -> {
                                                    var page = new QueryParamEntity();
                                                    page.doPaging(
                                                            input.getPageIndex(),
                                                            input.getPageSize());
                                                    page.setParallelPager(true);
                                                    var query =
                                                            eligible(
                                                                    prepared.thread()
                                                                            .getProjectId(),
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

    public Mono<ReviewAssignmentResponse> assign(
            String versionId, String threadId, ReviewAssignmentRequest input) {
        return threadAccess
                .currentThread(versionId, threadId, input)
                .flatMap(
                        prepared ->
                                lockUsers(prepared.actorId(), input.getAssigneeId())
                                        .flatMap(
                                                active ->
                                                        threadAccess
                                                                .lockedThread(
                                                                        versionId, input, prepared)
                                                                .flatMap(
                                                                        thread ->
                                                                                assign(
                                                                                        thread,
                                                                                        prepared
                                                                                                .actorId(),
                                                                                        input,
                                                                                        active)))
                                        .as(transactions::transactional))
                .checkpoint("prototype-hub/review/assignment");
    }

    private Mono<Boolean> lockUsers(String actor, String target) {
        // 无效旧目标仍须允许已提交请求重放；新请求在回执之后才使用当前资格判断。
        return Flux.fromStream(
                        () -> Stream.of(actor, target).filter(Objects::nonNull).distinct().sorted())
                .concatMap(
                        id ->
                                access.tryLockActiveUser(id)
                                        .map(active -> new LockedUser(id, active)),
                        1)
                .collectList()
                .map(
                        rows ->
                                target == null
                                        || rows.stream()
                                                .anyMatch(
                                                        row ->
                                                                target.equals(row.id())
                                                                        && row.active()));
    }

    private Mono<ReviewAssignmentResponse> assign(
            PrototypeReviewThreadEntity thread,
            String actor,
            ReviewAssignmentRequest input,
            boolean targetActive) {
        var request =
                receipts.request(
                        "member:null:" + actor,
                        "review-assignment",
                        receipts.request(
                                        "review",
                                        "scope",
                                        "scope",
                                        "scope",
                                        thread.getProjectId(),
                                        thread.getVersionId(),
                                        thread.getRevisionId(),
                                        null,
                                        thread.getPageId(),
                                        thread.getId())
                                .fingerprint(),
                        input.getClientRequestId(),
                        thread.getProjectId(),
                        thread.getVersionId(),
                        thread.getRevisionId(),
                        thread.getPageId(),
                        thread.getId(),
                        input.getAssigneeId(),
                        input.getExpectedVersion().toString());
        // 当前调用者和线程已重新授权；精确重放返回当前分派，不恢复旧目标、不再次消耗版本。
        return receipts.replay(request, id -> response(thread))
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    if (version(thread) != input.getExpectedVersion())
                                        return Mono.error(conflict());
                                    Mono<String> name =
                                            input.getAssigneeId() == null
                                                    ? Mono.just("")
                                                    : !targetActive
                                                            ? Mono.error(invalidTarget())
                                                            : owner(thread.getProjectId())
                                                                    .flatMap(
                                                                            owner ->
                                                                                    eligible(
                                                                                                    thread
                                                                                                            .getProjectId(),
                                                                                                    owner)
                                                                                            .where(
                                                                                                    condition ->
                                                                                                            condition
                                                                                                                    .is(
                                                                                                                            UserEntity
                                                                                                                                    ::getId,
                                                                                                                            input
                                                                                                                                    .getAssigneeId()))
                                                                                            .fetch()
                                                                                            .singleOrEmpty()
                                                                                            .map(
                                                                                                    AssigneeRow
                                                                                                            ::getName))
                                                                    .switchIfEmpty(
                                                                            Mono.error(
                                                                                    PrototypeReviewAssignmentService
                                                                                            ::invalidTarget));
                                    return name.flatMap(
                                            snapshot -> {
                                                // 只有旧回执可略过目标资格；新键的同值操作也必须锁内检查有效候选。
                                                Mono<Void> changed =
                                                        Objects.equals(
                                                                        thread.getAssigneeId(),
                                                                        input.getAssigneeId())
                                                                ? Mono.empty()
                                                                : change(
                                                                        thread,
                                                                        input.getAssigneeId(),
                                                                        snapshot);
                                                return changed.then(
                                                                receipts.record(
                                                                        request,
                                                                        thread.getId(),
                                                                        thread.getRevisionId()))
                                                        .then(Mono.defer(() -> response(thread)));
                                            });
                                }));
    }

    private Mono<Void> change(PrototypeReviewThreadEntity thread, String target, String name) {
        long previous = version(thread);
        if (previous == Long.MAX_VALUE) return Mono.error(conflict());
        long now = System.currentTimeMillis();
        var update =
                threads.createUpdate()
                        .set(PrototypeReviewThreadEntity::getAssignmentVersion, previous + 1)
                        .set(PrototypeReviewThreadEntity::getAssignmentUpdatedAt, now);
        if (target == null)
            update.setNull(PrototypeReviewThreadEntity::getAssigneeId)
                    .setNull(PrototypeReviewThreadEntity::getAssigneeName);
        else
            update.set(PrototypeReviewThreadEntity::getAssigneeId, target)
                    .set(PrototypeReviewThreadEntity::getAssigneeName, name);
        var where = update.where(PrototypeReviewThreadEntity::getId, thread.getId());
        if (thread.getAssignmentVersion() == null)
            where.isNull(PrototypeReviewThreadEntity::getAssignmentVersion);
        else where.and(PrototypeReviewThreadEntity::getAssignmentVersion, previous);
        return where.execute()
                .flatMap(rows -> rows == 1 ? Mono.empty() : Mono.error(conflict()))
                .then(
                        Mono.defer(
                                () -> {
                                    thread.setAssigneeId(target);
                                    thread.setAssigneeName(target == null ? null : name);
                                    thread.setAssignmentVersion(previous + 1);
                                    thread.setAssignmentUpdatedAt(now);
                                    var event = new PrototypeReviewEventEntity();
                                    event.setId(PrototypeReviewKeys.random());
                                    event.setProjectId(thread.getProjectId());
                                    event.setVersionId(thread.getVersionId());
                                    event.setRevisionId(thread.getRevisionId());
                                    event.setThreadId(thread.getId());
                                    event.setEventType(
                                            target == null
                                                    ? "thread.unassigned"
                                                    : "thread.assigned");
                                    event.setActorType("member");
                                    event.setCreateTime(now);
                                    return events.insert(event).then();
                                }));
    }

    private Mono<ReviewAssignmentResponse> response(PrototypeReviewThreadEntity thread) {
        if (thread.getAssigneeId() == null) return Mono.just(response(thread, false));
        return owner(thread.getProjectId())
                .flatMap(
                        owner ->
                                eligible(thread.getProjectId(), owner)
                                        .where(
                                                condition ->
                                                        condition.is(
                                                                UserEntity::getId,
                                                                thread.getAssigneeId()))
                                        .fetch()
                                        .singleOrEmpty()
                                        .hasElement())
                .map(available -> response(thread, available));
    }

    private static ReviewAssignmentResponse response(
            PrototypeReviewThreadEntity thread, boolean available) {
        return new ReviewAssignmentResponse(
                thread.getId(),
                version(thread),
                thread.getAssignmentUpdatedAt(),
                thread.getAssigneeId() == null
                        ? null
                        : new ReviewAssignmentResponse.Assignee(
                                thread.getAssigneeId(), thread.getAssigneeName(), available));
    }

    private Mono<String> owner(String projectId) {
        return projects.createQuery()
                .select(PrototypeProjectEntity::getOwnerId)
                .where(PrototypeProjectEntity::getId, projectId)
                .fetch()
                .singleOrEmpty()
                .map(row -> row.getOwnerId() == null ? "" : row.getOwnerId());
    }

    /** 唯一成员索引保证用户根LEFT JOIN无重复；资格和字面筛选均先于COUNT/DB分页。 */
    private QueryHelper.JoinSpec<AssigneeRow> eligible(String projectId, String owner) {
        return eligible(projectId, owner, null);
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
                                                .and(
                                                        PrototypeProjectMemberEntity::getRole,
                                                        "editor"));
        // where(QueryParamEntity)替换参数，必须先应用分页，再追加不可覆盖的服务端资格。
        if (page != null) query.where(page);
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

    /** 项目批次至多一百项；线程keyset每次一百行，不把该用户的所有线程收集到内存。 */
    public Mono<Set<String>> assignedProjects(List<String> projectIds, String userId) {
        return Mono.just(new AssignedProjects("", Set.of()))
                .expand(
                        cursor ->
                                threads.createQuery()
                                        .select(
                                                PrototypeReviewThreadEntity::getId,
                                                PrototypeReviewThreadEntity::getProjectId)
                                        .where(PrototypeReviewThreadEntity::getAssigneeId, userId)
                                        .in(PrototypeReviewThreadEntity::getProjectId, projectIds)
                                        .gt(PrototypeReviewThreadEntity::getId, cursor.after())
                                        .orderBy(SortOrder.asc(PrototypeReviewThreadEntity::getId))
                                        .paging(0, 100)
                                        .fetch()
                                        .collectList()
                                        .flatMap(
                                                rows -> {
                                                    if (rows.isEmpty()) return Mono.empty();
                                                    Set<String> found =
                                                            new HashSet<>(cursor.projects());
                                                    rows.forEach(
                                                            row -> found.add(row.getProjectId()));
                                                    return Mono.just(
                                                            new AssignedProjects(
                                                                    rows.getLast().getId(), found));
                                                }),
                        1)
                .last()
                .map(AssignedProjects::projects);
    }

    private record AssignedProjects(String after, Set<String> projects) {}

    /** 仅账号删除事务调用；已持用户/项目锁，每批最多五十条并逐条维护各自CAS版本。 */
    public Mono<Void> clearDeletedUser(String projectId, String userId) {
        return assignedBatch(projectId, userId)
                .expand(
                        rows ->
                                rows.isEmpty()
                                        ? Mono.empty()
                                        : Flux.fromIterable(rows)
                                                .concatMap(row -> change(row, null, null), 1)
                                                .then(assignedBatch(projectId, userId)),
                        1)
                .then();
    }

    private Mono<List<PrototypeReviewThreadEntity>> assignedBatch(String projectId, String userId) {
        return threads.createQuery()
                .where(PrototypeReviewThreadEntity::getProjectId, projectId)
                .and(PrototypeReviewThreadEntity::getAssigneeId, userId)
                .orderBy(SortOrder.asc(PrototypeReviewThreadEntity::getId))
                .paging(0, 50)
                .fetch()
                .collectList();
    }

    private static long version(PrototypeReviewThreadEntity thread) {
        return thread.getAssignmentVersion() == null ? 0 : thread.getAssignmentVersion();
    }

    private static PrototypeHubException invalidTarget() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.review_assignee_invalid",
                "PROTOTYPE_HUB_REVIEW_ASSIGNEE_INVALID");
    }

    private static PrototypeHubException conflict() {
        return new PrototypeHubException(
                "error.prototype-hub.review_assignment_conflict",
                "PROTOTYPE_HUB_REVIEW_ASSIGNMENT_CONFLICT",
                409);
    }

    private record LockedUser(String id, boolean active) {}

    @Getter
    @Setter
    public static class AssigneeRow {
        private String userId;
        private String name;
    }
}
