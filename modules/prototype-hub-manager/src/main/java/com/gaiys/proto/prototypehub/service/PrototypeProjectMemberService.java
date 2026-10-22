package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypePreviewSessionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.enums.ProjectRole;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.web.response.MemberCandidateResponse;
import com.gaiys.proto.prototypehub.web.response.ProjectMemberResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.api.crud.entity.RecordModifierEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.hswebframework.web.system.authorization.api.service.reactive.ReactiveUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 项目成员的唯一写入入口；成员修改和预览授权代次切换在同一项目锁事务提交。 */
@Service
@RequiredArgsConstructor
public class PrototypeProjectMemberService {
    private final PrototypeProjectAccessService access;
    private static final int CANDIDATE_BATCH = 100;
    private final ReactiveUserService users;
    private final ReactiveRepository<UserEntity, String> userRepository;
    private final ReactiveRepository<PrototypeProjectEntity, String> projects;
    private final ReactiveRepository<PrototypeProjectMemberEntity, String> bindings;
    private final ReactiveRepository<PrototypePreviewSessionEntity, String> sessions;
    private final QueryHelper queries;
    private final TransactionalOperator transactions;

    public Flux<ProjectMemberResponse> members(String projectId) {
        return access.requireProject(projectId, Action.MEMBERS)
                .flatMapMany(
                        grant -> {
                            String ownerId = grant.project().getOwnerId();
                            Mono<ProjectMemberResponse> owner =
                                    ownerId == null
                                            ? Mono.empty()
                                            : users.findById(ownerId)
                                                    .map(
                                                            user ->
                                                                    new ProjectMemberResponse(
                                                                            ownerId,
                                                                            user.getName(),
                                                                            user.getUsername(),
                                                                            "owner"))
                                                    .defaultIfEmpty(
                                                            new ProjectMemberResponse(
                                                                    ownerId, null, null, "owner"));
                            Flux<ProjectMemberResponse> members =
                                    queries.select(MemberRow.class)
                                            .as(
                                                    PrototypeProjectMemberEntity::getUserId,
                                                    MemberRow::setUserId)
                                            .as(
                                                    PrototypeProjectMemberEntity::getRole,
                                                    MemberRow::setRole)
                                            .as(UserEntity::getName, MemberRow::setName)
                                            .as(UserEntity::getUsername, MemberRow::setUsername)
                                            .from(PrototypeProjectMemberEntity.class)
                                            .leftJoin(
                                                    UserEntity.class,
                                                    join ->
                                                            join.is(
                                                                    UserEntity::getId,
                                                                    PrototypeProjectMemberEntity
                                                                            ::getUserId))
                                            .where(
                                                    condition ->
                                                            condition.is(
                                                                    PrototypeProjectMemberEntity
                                                                            ::getProjectId,
                                                                    projectId))
                                            .orderByAsc(PrototypeProjectMemberEntity::getUserId)
                                            .fetch()
                                            .map(
                                                    row ->
                                                            new ProjectMemberResponse(
                                                                    row.getUserId(),
                                                                    row.getName(),
                                                                    row.getUsername(),
                                                                    row.getRole()));
                            return owner.flux().concatWith(members);
                        });
    }

    public Flux<MemberCandidateResponse> candidates(String projectId, String keyword) {
        String term = keyword == null ? "" : keyword.trim();
        if (term.length() > 128)
            return Flux.error(
                    PrototypeHubException.badRequest(
                            "error.prototype-hub.member_keyword_invalid",
                            "PROTOTYPE_HUB_MEMBER_KEYWORD_INVALID"));
        // 有界 keyset 读取后按字面匹配；%/_ 不变成通配符，且不依赖各数据库的转义与大小写函数。
        return access.requireProject(projectId, Action.MEMBERS)
                .flatMapMany(
                        grant ->
                                Mono.just(new CandidateCursor(null, null, 20, List.of(), false))
                                        .expand(
                                                cursor ->
                                                        cursor.complete()
                                                                ? Mono.empty()
                                                                : candidateBatch(
                                                                        grant.project()
                                                                                .getOwnerId(),
                                                                        term.toLowerCase(
                                                                                Locale.ROOT),
                                                                        cursor),
                                                1)
                                        .concatMapIterable(CandidateCursor::matches, 1));
    }

    private Mono<CandidateCursor> candidateBatch(
            String ownerId, String keyword, CandidateCursor cursor) {
        var query =
                userRepository
                        .createQuery()
                        .select(UserEntity::getId, UserEntity::getName, UserEntity::getUsername)
                        .where(UserEntity::getStatus, (byte) 1);
        if (ownerId != null) query.not(UserEntity::getId, ownerId);
        if (cursor.id() != null)
            query.nest()
                    .gt(UserEntity::getUsername, cursor.username())
                    .orNest()
                    .is(UserEntity::getUsername, cursor.username())
                    .gt(UserEntity::getId, cursor.id())
                    .end()
                    .end();
        return query.orderBy(
                        SortOrder.asc(UserEntity::getUsername), SortOrder.asc(UserEntity::getId))
                .paging(0, CANDIDATE_BATCH)
                .fetch()
                .collectList()
                .map(
                        page -> {
                            if (page.isEmpty())
                                return new CandidateCursor(
                                        cursor.username(),
                                        cursor.id(),
                                        cursor.remaining(),
                                        List.of(),
                                        true);
                            List<MemberCandidateResponse> found =
                                    page.stream()
                                            .filter(
                                                    user ->
                                                            contains(user.getName(), keyword)
                                                                    || contains(
                                                                            user.getUsername(),
                                                                            keyword))
                                            .limit(cursor.remaining())
                                            .map(
                                                    user ->
                                                            new MemberCandidateResponse(
                                                                    user.getId(),
                                                                    user.getName(),
                                                                    user.getUsername()))
                                            .toList();
                            UserEntity last = page.getLast();
                            int remaining = cursor.remaining() - found.size();
                            return new CandidateCursor(
                                    last.getUsername(),
                                    last.getId(),
                                    remaining,
                                    found,
                                    remaining == 0 || page.size() < CANDIDATE_BATCH);
                        });
    }

    /** 仅保留排序游标及最多 20 条响应，不让递归订阅链累积用户实体页。 */
    private record CandidateCursor(
            String username,
            String id,
            int remaining,
            List<MemberCandidateResponse> matches,
            boolean complete) {}

    private static boolean contains(String value, String keyword) {
        return (value == null ? "" : value).toLowerCase(Locale.ROOT).contains(keyword);
    }

    /** 仅供 QueryHelper 映射成员关联结果，不作为 HTTP 响应实体。 */
    @Getter
    @Setter
    public static class MemberRow {
        private String userId;
        private String name;
        private String username;
        private String role;
    }

    public Mono<ProjectMemberResponse> put(String projectId, String userId, String role) {
        if (!ProjectRole.editor.name().equals(role) && !ProjectRole.viewer.name().equals(role))
            return Mono.error(PrototypeProjectAccessService.roleDenied());
        return access.current()
                .flatMap(actor -> access.lockActiveUsers(actor.userId(), userId))
                .then(access.lockProject(projectId))
                .then(access.requireProject(projectId, Action.MEMBERS))
                .flatMap(
                        grant ->
                                rejectOwner(grant.project(), userId)
                                        .then(access.activeUser(userId)))
                .flatMap(
                        user ->
                                putBinding(projectId, userId, role)
                                        .then(rotate(projectId))
                                        .thenReturn(
                                                new ProjectMemberResponse(
                                                        user.getId(),
                                                        user.getName(),
                                                        user.getUsername(),
                                                        role)))
                .as(transactions::transactional);
    }

    public Mono<Boolean> remove(String projectId, String userId) {
        return access.lockProject(projectId)
                .then(access.requireProject(projectId, Action.MEMBERS))
                .flatMap(grant -> rejectOwner(grant.project(), userId))
                .then(
                        bindings.createDelete()
                                .where(PrototypeProjectMemberEntity::getProjectId, projectId)
                                .and(PrototypeProjectMemberEntity::getUserId, userId)
                                .execute())
                .flatMap(rows -> rotate(projectId).thenReturn(rows > 0))
                .as(transactions::transactional);
    }

    public Mono<Void> rotate(String projectId) {
        // 所有成员会话一起换代；撤员后重新添加也不能恢复先前已泄漏的内部预览 URL。
        // 授权谱系变更不修改项目编辑审计，使用官方标记仅关闭自动 modifier 补写。
        return projects.createUpdate()
                .set(PrototypeProjectEntity::getAccessEpoch, UUID.randomUUID().toString())
                .where(PrototypeProjectEntity::getId, projectId)
                .execute()
                .contextWrite(RecordModifierEntity::markDoNotUpdate)
                .then(
                        sessions.createDelete()
                                .where(PrototypePreviewSessionEntity::getProjectId, projectId)
                                .execute())
                .then();
    }

    private Mono<Void> putBinding(String projectId, String userId, String role) {
        // 调用方已持项目行锁，同一项目的授权写入串行；先读后更新/插入不会依赖方言 upsert。
        long now = System.currentTimeMillis();
        return bindings.createQuery()
                .select(PrototypeProjectMemberEntity::getId)
                .where(PrototypeProjectMemberEntity::getProjectId, projectId)
                .and(PrototypeProjectMemberEntity::getUserId, userId)
                .fetchOne()
                .flatMap(
                        existing ->
                                bindings.createUpdate()
                                        .set(PrototypeProjectMemberEntity::getRole, role)
                                        .set(PrototypeProjectMemberEntity::getCreateTime, now)
                                        .where(
                                                PrototypeProjectMemberEntity::getId,
                                                existing.getId())
                                        .execute()
                                        .thenReturn(true))
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    PrototypeProjectMemberEntity binding =
                                            new PrototypeProjectMemberEntity();
                                    binding.setId(UUID.randomUUID().toString());
                                    binding.setProjectId(projectId);
                                    binding.setUserId(userId);
                                    binding.setRole(role);
                                    binding.setCreateTime(now);
                                    return bindings.insert(binding).thenReturn(true);
                                }))
                .then();
    }

    private Mono<Void> rejectOwner(PrototypeProjectEntity project, String userId) {
        return userId.equals(project.getOwnerId())
                ? Mono.error(
                        PrototypeHubException.badRequest(
                                "error.prototype-hub.owner_member_immutable",
                                "PROTOTYPE_HUB_OWNER_MEMBER_IMMUTABLE"))
                : Mono.empty();
    }
}
