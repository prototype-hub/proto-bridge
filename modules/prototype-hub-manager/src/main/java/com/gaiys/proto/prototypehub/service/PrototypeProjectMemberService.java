package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.enums.ProjectRole;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.web.response.MemberCandidateResponse;
import com.gaiys.proto.prototypehub.web.response.ProjectMemberResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 项目成员的唯一写入入口；成员修改和预览授权代次切换在同一项目锁事务提交。 */
@Service
@RequiredArgsConstructor
public class PrototypeProjectMemberService {
    private final PrototypeProjectAccessService access;
    private final DatabaseClient database;
    private final TransactionalOperator transactions;

    public Flux<ProjectMemberResponse> members(String projectId) {
        return access.requireProject(projectId, Action.MEMBERS)
                .thenMany(
                        database.sql(
                                        """
select p.owner_id as user_id,u.name,u.username,'owner' as role,0 as ordering
from p_project p left join s_user u on u.id=p.owner_id
where p.id=:projectId and p.owner_id is not null
union all
select m.user_id,u.name,u.username,m.role,1 as ordering
from p_project_member m left join s_user u on u.id=m.user_id
where m.project_id=:projectId
order by ordering,user_id
""")
                                .bind("projectId", projectId)
                                .map(
                                        (row, metadata) ->
                                                new ProjectMemberResponse(
                                                        row.get("user_id", String.class),
                                                        row.get("name", String.class),
                                                        row.get("username", String.class),
                                                        row.get("role", String.class)))
                                .all());
    }

    public Flux<MemberCandidateResponse> candidates(String projectId, String keyword) {
        String term = keyword == null ? "" : keyword.trim();
        if (term.length() > 128)
            return Flux.error(
                    PrototypeHubException.badRequest(
                            "error.prototype-hub.member_keyword_invalid",
                            "PROTOTYPE_HUB_MEMBER_KEYWORD_INVALID"));
        // position 按字面匹配，输入中的 %/_ 不会变成 SQL 通配符；只暴露最多 20 个必要身份字段。
        return access.requireProject(projectId, Action.MEMBERS)
                .thenMany(
                        database.sql(
                                        """
select u.id,u.name,u.username from s_user u
where u.status=1 and (position(lower(:keyword) in lower(coalesce(u.name,'')))>0 or position(lower(:keyword) in lower(coalesce(u.username,'')))>0)
and not exists(select 1 from p_project p where p.id=:projectId and p.owner_id=u.id)
order by u.username,u.id limit 20
""")
                                .bind("keyword", term)
                                .bind("projectId", projectId)
                                .map(
                                        (row, metadata) ->
                                                new MemberCandidateResponse(
                                                        row.get("id", String.class),
                                                        row.get("name", String.class),
                                                        row.get("username", String.class)))
                                .all());
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
                                database.sql(
                                                """
insert into p_project_member(id,project_id,user_id,role,create_time)
values(:id,:projectId,:userId,:role,:createdAt)
on conflict(project_id,user_id) do update set role=excluded.role,create_time=excluded.create_time
""")
                                        .bind("id", UUID.randomUUID().toString())
                                        .bind("projectId", projectId)
                                        .bind("userId", userId)
                                        .bind("role", role)
                                        .bind("createdAt", System.currentTimeMillis())
                                        .fetch()
                                        .rowsUpdated()
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
                        database.sql(
                                        "delete from p_project_member where project_id=:projectId"
                                                + " and user_id=:userId")
                                .bind("projectId", projectId)
                                .bind("userId", userId)
                                .fetch()
                                .rowsUpdated())
                .flatMap(rows -> rotate(projectId).thenReturn(rows > 0))
                .as(transactions::transactional);
    }

    public Mono<Void> rotate(String projectId) {
        // 所有成员会话一起换代；撤员后重新添加也不能恢复先前已泄漏的内部预览 URL。
        return database.sql("update p_project set access_epoch=:epoch where id=:id")
                .bind("epoch", UUID.randomUUID().toString())
                .bind("id", projectId)
                .fetch()
                .rowsUpdated()
                .then(
                        database.sql("delete from p_preview_session where project_id=:id")
                                .bind("id", projectId)
                                .fetch()
                                .rowsUpdated())
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
