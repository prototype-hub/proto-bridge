package com.gaiys.proto.prototypehub.service.query;

import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.enums.ProjectRole;
import com.gaiys.proto.prototypehub.web.response.ProjectResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 关联范围由官方 QueryHelper 下推数据库，集中映射项目角色及空白旧 owner 的业务含义。
 *
 * <p>由 Spring 单例管理，每次调用创建独立查询，不缓存查询结果或授权状态。
 */
@Component
@RequiredArgsConstructor
public class PrototypeScopeQuery {
    private final QueryHelper queries;

    public Flux<ProjectResponse> projects(String userId, boolean administrator) {
        var query =
                queries.select(ProjectScope.class)
                        .all(PrototypeProjectEntity.class, ProjectScope::setProject)
                        .as(PrototypeProjectMemberEntity::getRole, ProjectScope::setMemberRole)
                        .as(UserEntity::getStatus, ProjectScope::setOwnerStatus)
                        .from(PrototypeProjectEntity.class)
                        .leftJoin(
                                PrototypeProjectMemberEntity.class,
                                join ->
                                        join.is(
                                                        PrototypeProjectMemberEntity::getProjectId,
                                                        PrototypeProjectEntity::getId)
                                                .and(
                                                        PrototypeProjectMemberEntity::getUserId,
                                                        userId))
                        .leftJoin(
                                UserEntity.class,
                                join ->
                                        join.is(
                                                UserEntity::getId,
                                                PrototypeProjectEntity::getOwnerId));
        var scoped =
                administrator
                        ? query
                        : query.where(
                                condition ->
                                        condition
                                                .nest()
                                                .is(PrototypeProjectEntity::getOwnerId, userId)
                                                .or()
                                                .in(
                                                        PrototypeProjectMemberEntity::getRole,
                                                        "editor",
                                                        "viewer")
                                                .end());
        // 成员/owner 范围已在数据库收窄；仅排除无有效 owner 的历史项目，不先读取全表再鉴权。
        return scoped.orderByDesc(PrototypeProjectEntity::getCreateTime)
                .orderByAsc(PrototypeProjectEntity::getId)
                .fetch()
                .filter(row -> administrator || hasText(row.getProject().getOwnerId()))
                .map(
                        row ->
                                ProjectResponse.from(
                                        row.getProject(),
                                        administrator
                                                ? ProjectRole.admin.name()
                                                : userId.equals(row.getProject().getOwnerId())
                                                        ? ProjectRole.owner.name()
                                                        : row.getMemberRole(),
                                        !Byte.valueOf((byte) 1).equals(row.getOwnerStatus())));
    }

    public Flux<PrototypeVersionEntity> versions(String userId, boolean administrator) {
        var query =
                queries.select(VersionScope.class)
                        .all(PrototypeVersionEntity.class, VersionScope::setVersion)
                        .as(PrototypeProjectEntity::getOwnerId, VersionScope::setOwnerId)
                        .from(PrototypeVersionEntity.class)
                        .innerJoin(
                                PrototypeProjectEntity.class,
                                join ->
                                        join.is(
                                                PrototypeProjectEntity::getId,
                                                PrototypeVersionEntity::getProjectId))
                        .leftJoin(
                                PrototypeProjectMemberEntity.class,
                                join ->
                                        join.is(
                                                        PrototypeProjectMemberEntity::getProjectId,
                                                        PrototypeProjectEntity::getId)
                                                .and(
                                                        PrototypeProjectMemberEntity::getUserId,
                                                        userId));
        var scoped =
                administrator
                        ? query
                        : query.where(
                                condition ->
                                        condition
                                                .nest()
                                                .is(PrototypeProjectEntity::getOwnerId, userId)
                                                .or()
                                                .in(
                                                        PrototypeProjectMemberEntity::getRole,
                                                        "editor",
                                                        "viewer")
                                                .end());
        return scoped.orderByDesc(PrototypeVersionEntity::getCreateTime)
                .orderByAsc(PrototypeVersionEntity::getId)
                .fetch()
                .filter(row -> administrator || hasText(row.getOwnerId()))
                .map(VersionScope::getVersion);
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    /** 仅供 QueryHelper 映射关联结果，不作为 HTTP 响应实体。 */
    @Getter
    @Setter
    public static class ProjectScope {
        private PrototypeProjectEntity project;
        private String memberRole;
        private Byte ownerStatus;
    }

    /** 仅供 QueryHelper 映射关联结果，不作为 HTTP 响应实体。 */
    @Getter
    @Setter
    public static class VersionScope {
        private PrototypeVersionEntity version;
        private String ownerId;
    }
}
