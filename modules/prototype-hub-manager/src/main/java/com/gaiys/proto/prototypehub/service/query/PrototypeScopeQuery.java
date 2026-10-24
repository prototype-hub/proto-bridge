package com.gaiys.proto.prototypehub.service.query;

import com.gaiys.proto.prototypehub.configuration.builder.PrototypeTextTermFragmentBuilder;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.enums.ProjectRole;
import com.gaiys.proto.prototypehub.service.PrototypeRetentionService;
import com.gaiys.proto.prototypehub.web.request.PrototypeListQueryRequest;
import com.gaiys.proto.prototypehub.web.response.ProjectResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
        return projectQuery(userId, administrator, null, false)
                .orderByDesc(PrototypeProjectEntity::getCreateTime)
                .orderByAsc(PrototypeProjectEntity::getId)
                .fetch()
                .map(row -> projectResponse(row, userId, administrator));
    }

    /** 授权与关键词均在 COUNT 和分页前注入，同一时间值由 id 保持稳定顺序。 */
    public Mono<PagerResult<ProjectResponse>> projects(
            String userId, boolean administrator, PrototypeListQueryRequest request) {
        return projects(userId, administrator, request, false);
    }

    public Mono<PagerResult<ProjectResponse>> deletedProjects(
            String userId, boolean administrator, PrototypeListQueryRequest request) {
        return projects(userId, administrator, request, true);
    }

    private Mono<PagerResult<ProjectResponse>> projects(
            String userId,
            boolean administrator,
            PrototypeListQueryRequest request,
            boolean deleted) {
        var query = projectQuery(userId, administrator, pageParameter(request), deleted);
        String keyword = keyword(request);
        if (!keyword.isEmpty())
            query.where(
                    condition ->
                            condition.accept(
                                    PrototypeProjectEntity::getName,
                                    PrototypeTextTermFragmentBuilder.LITERAL_CONTAINS.getTermType(),
                                    keyword));
        var order = order(request);
        if ("name".equals(request.getSortBy()))
            query.orderBy(PrototypeProjectEntity::getName, order);
        else query.orderBy(PrototypeProjectEntity::getCreateTime, order);
        return query.orderByAsc(PrototypeProjectEntity::getId)
                .fetchPaged(
                        rows ->
                                Mono.just(
                                        rows.stream()
                                                .map(
                                                        row ->
                                                                projectResponse(
                                                                        row, userId, administrator))
                                                .toList()));
    }

    private QueryHelper.JoinSpec<ProjectScope> projectQuery(
            String userId, boolean administrator, QueryParamEntity page, boolean deleted) {
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
        if (page != null) query.where(page);
        if (deleted) {
            long cutoff = System.currentTimeMillis() - PrototypeRetentionService.RETENTION_MILLIS;
            query.where(condition -> condition.gt(PrototypeProjectEntity::getDeletedTime, cutoff));
            if (!administrator)
                query.where(condition -> condition.is(PrototypeProjectEntity::getOwnerId, userId));
        } else applyScope(query, userId, administrator, false);
        return query;
    }

    private static ProjectResponse projectResponse(
            ProjectScope row, String userId, boolean administrator) {
        return ProjectResponse.from(
                row.getProject(),
                administrator
                        ? ProjectRole.admin.name()
                        : userId.equals(row.getProject().getOwnerId())
                                ? ProjectRole.owner.name()
                                : row.getMemberRole(),
                !Byte.valueOf((byte) 1).equals(row.getOwnerStatus()));
    }

    public Flux<PrototypeVersionEntity> versions(String userId, boolean administrator) {
        return versionQuery(userId, administrator, null, false)
                .orderByDesc(PrototypeVersionEntity::getCreateTime)
                .orderByAsc(PrototypeVersionEntity::getId)
                .fetch()
                .map(VersionScope::getVersion);
    }

    /** 父项目范围由调用方先校验；这里仍将同一授权谓词与可选项目条件下推两条分页查询。 */
    public Mono<PagerResult<PrototypeVersionEntity>> versions(
            String userId,
            boolean administrator,
            String projectId,
            PrototypeListQueryRequest request) {
        return versions(userId, administrator, projectId, request, false);
    }

    public Mono<PagerResult<PrototypeVersionEntity>> deletedVersions(
            String userId,
            boolean administrator,
            String projectId,
            PrototypeListQueryRequest request) {
        return versions(userId, administrator, projectId, request, true);
    }

    private Mono<PagerResult<PrototypeVersionEntity>> versions(
            String userId,
            boolean administrator,
            String projectId,
            PrototypeListQueryRequest request,
            boolean deleted) {
        var query = versionQuery(userId, administrator, pageParameter(request), deleted);
        String keyword = keyword(request);
        if (!keyword.isEmpty())
            query.where(
                    condition ->
                            condition.accept(
                                    PrototypeVersionEntity::getVersionName,
                                    PrototypeTextTermFragmentBuilder.LITERAL_CONTAINS.getTermType(),
                                    keyword));
        if (projectId != null && !projectId.isBlank())
            query.where(condition -> condition.is(PrototypeVersionEntity::getProjectId, projectId));
        var order = order(request);
        if ("name".equals(request.getSortBy()))
            query.orderBy(PrototypeVersionEntity::getVersionName, order);
        else query.orderBy(PrototypeVersionEntity::getCreateTime, order);
        return query.orderByAsc(PrototypeVersionEntity::getId)
                .fetchPaged(
                        rows -> Mono.just(rows.stream().map(VersionScope::getVersion).toList()));
    }

    private QueryHelper.JoinSpec<VersionScope> versionQuery(
            String userId, boolean administrator, QueryParamEntity page, boolean deleted) {
        var query =
                queries.select(VersionScope.class)
                        .all(PrototypeVersionEntity.class, VersionScope::setVersion)
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
        if (page != null) query.where(page);
        long cutoff = System.currentTimeMillis() - PrototypeRetentionService.RETENTION_MILLIS;
        query.where(
                condition -> {
                    if (deleted) condition.gt(PrototypeVersionEntity::getDeletedTime, cutoff);
                    else condition.isNull(PrototypeVersionEntity::getDeletedTime);
                });
        applyScope(query, userId, administrator, deleted);
        return query;
    }

    private static <T> void applyScope(
            QueryHelper.JoinSpec<T> query, String userId, boolean administrator, boolean editOnly) {
        // 删除状态约束也适用于 ADMIN，且必须在 COUNT/分页之前注入。
        query.where(condition -> condition.isNull(PrototypeProjectEntity::getDeletedTime));
        if (administrator) return;
        // owner 的空白判断与按 ID 详情一致，不能在分页后过滤，否则总数和页边界会泄漏或缺项。
        query.where(
                condition ->
                        condition
                                .accept(
                                        PrototypeProjectEntity::getOwnerId,
                                        PrototypeTextTermFragmentBuilder.OWNER_PRESENT
                                                .getTermType(),
                                        true)
                                .nest()
                                .is(PrototypeProjectEntity::getOwnerId, userId)
                                .or()
                                .in(
                                        PrototypeProjectMemberEntity::getRole,
                                        editOnly
                                                ? new String[] {"editor"}
                                                : new String[] {"editor", "viewer"})
                                .end());
    }

    private static QueryParamEntity pageParameter(PrototypeListQueryRequest request) {
        QueryParamEntity page = new QueryParamEntity();
        page.doPaging(request.getPageIndex(), request.getPageSize());
        // 默认串行分支会按总数自动 rePaging；并行策略保留请求页码，越界返回真实 total 和空页。
        // COUNT 与数据使用普通查询隔离，不承诺与并发写入之间的强快照。
        page.setParallelPager(true);
        return page;
    }

    private static String keyword(PrototypeListQueryRequest request) {
        return request.getKeyword() == null ? "" : request.getKeyword().strip();
    }

    private static SortOrder.Order order(PrototypeListQueryRequest request) {
        return "asc".equals(request.getSortOrder()) ? SortOrder.Order.asc : SortOrder.Order.desc;
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
    }
}
