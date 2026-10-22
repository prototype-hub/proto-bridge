package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.enums.ProjectRole;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.web.response.ProjectCapabilities;
import com.gaiys.proto.prototypehub.web.response.ProjectResponse;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.ReactiveAuthenticationManager;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.hswebframework.web.system.authorization.api.service.reactive.ReactiveUserService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 原型资产范围的唯一提供方。
 *
 * <p>当前开源依赖不包含 JetLinks AssetsHolder；在本适配边界统一实现项目绑定、关联资源回溯和范围查询， 不在各控制器拼接创建者条件。hsweb
 * 动作注解仍为另一必要条件，项目角色不会额外授予菜单动作。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrototypeProjectAccessService {
    private final ReactiveRepository<PrototypeProjectEntity, String> projects;
    private final ReactiveRepository<PrototypeVersionEntity, String> versions;
    private final ReactiveRepository<PrototypeShareEntity, String> shares;
    private final ReactiveUserService users;
    private final ReactiveRepository<DimensionEntity, String> dimensions;
    private final ReactiveAuthenticationManager authentication;
    private final DatabaseClient database;

    public enum Action {
        READ,
        EDIT,
        MEMBERS,
        DELETE,
        SHARE
    }

    /** 活跃账号检查独立于登录令牌缓存，停用或删除后不可继续使用原型能力。 */
    public Mono<Actor> current() {
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException::new))
                .flatMap(this::actor);
    }

    public Mono<Actor> actor(Authentication auth) {
        return activeUser(auth.getUser().getId())
                .flatMap(
                        user ->
                                administrator(auth)
                                        .map(admin -> new Actor(user.getId(), auth, admin)));
    }

    private Mono<Boolean> administrator(Authentication auth) {
        if (!auth.hasDimension("role", "ADMIN")) return Mono.just(false);
        // hsweb 可保留 disabled 维度本身而只过滤其权限设置；例外必须同时核验当前角色与绑定。
        return dimensions
                .findById("ADMIN")
                .filter(
                        role ->
                                "role".equals(role.getTypeId())
                                        && (role.getProperties() == null
                                                || !"disabled"
                                                        .equals(role.getProperties().get("state"))))
                .flatMap(
                        role ->
                                database.sql(
                                                "select count(*) as total from s_dimension_user"
                                                        + " where dimension_type_id='role' and"
                                                        + " dimension_id='ADMIN' and user_id=:userId")
                                        .bind("userId", auth.getUser().getId())
                                        .map((row, metadata) -> row.get("total", Long.class) > 0)
                                        .one())
                .defaultIfEmpty(false);
    }

    public Mono<Actor> actor(String userId) {
        return authentication
                .getByUserId(userId)
                .switchIfEmpty(Mono.error(UnAuthorizedException::new))
                .flatMap(this::actor);
    }

    public Mono<UserEntity> activeUser(String userId) {
        if (userId == null || userId.isBlank()) return Mono.error(userUnavailable());
        return users.findById(userId)
                .filter(user -> Byte.valueOf((byte) 1).equals(user.getStatus()))
                .switchIfEmpty(Mono.error(PrototypeProjectAccessService::userUnavailable));
    }

    /**
     * 持久化授权绑定的生产者显式调用；必须处于实际事务内，且先于项目/安装行锁。
     *
     * <p>删除账号持有用户行排他锁直到其 UserDeletedEvent 清理完成。KEY SHARE 与删除互斥，保证事件 扫描之后不会提交遗漏的新
     * owner/member/session；普通身份查询不得隐式取得此锁，避免项目→用户倒序。
     */
    public Mono<Boolean> tryLockActiveUser(String userId) {
        if (userId == null || userId.isBlank()) return Mono.just(false);
        return database.sql("select id from s_user where id=:id and status=1 for key share")
                .bind("id", userId)
                .fetch()
                .all()
                .collectList()
                .map(rows -> !rows.isEmpty());
    }

    /** 多个账号始终按稳定顺序锁定；绑定的请求者与目标账号均须在提交前保持存在。 */
    public Mono<Void> lockActiveUsers(String... userIds) {
        if (Arrays.stream(userIds).anyMatch(id -> id == null || id.isBlank()))
            return Mono.error(userUnavailable());
        return Flux.fromStream(() -> Arrays.stream(userIds).distinct().sorted())
                .concatMap(
                        id ->
                                tryLockActiveUser(id)
                                        .flatMap(
                                                active ->
                                                        active
                                                                ? Mono.empty()
                                                                : Mono.error(userUnavailable())),
                        1)
                .then();
    }

    public Mono<Grant> requireProject(String id, Action action) {
        return current().flatMap(actor -> requireProject(actor, id, action, true));
    }

    public Mono<Grant> requireProject(Actor actor, String id, Action action, boolean audit) {
        return projects.findById(id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("project")))
                .flatMap(project -> role(actor, project).map(role -> new Grant(project, role)))
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("project")))
                .flatMap(
                        grant -> {
                            if (!allows(grant.role(), action)) return Mono.error(roleDenied());
                            // ADMIN 来自有效角色维度，不使用用户名/账号类型。只在管理入口记录例外，不逐静态资源刷日志。
                            if (audit && ProjectRole.admin.name().equals(grant.role())) {
                                log.info(
                                        "Prototype administrator scope access: actor={},"
                                                + " project={}, action={}",
                                        actor.userId(),
                                        id,
                                        action);
                            }
                            return Mono.just(grant);
                        });
    }

    public Mono<PrototypeVersionEntity> requireVersion(String id, Action action) {
        return current().flatMap(actor -> requireVersion(actor, id, action, true));
    }

    public Mono<PrototypeVersionEntity> requireVersion(
            Actor actor, String id, Action action, boolean audit) {
        return versions.findById(id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("version")))
                .flatMap(
                        version ->
                                requireProject(actor, version.getProjectId(), action, audit)
                                        .thenReturn(version));
    }

    public Mono<PrototypeShareEntity> requireShare(String id, Action action) {
        return shares.findById(id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("share")))
                .flatMap(
                        share ->
                                correlatedVersion(share)
                                        .then(requireProject(share.getProjectId(), action))
                                        .thenReturn(share));
    }

    /** 管理和公开分享均使用持久化关联；坏旧关联不能把 A 的分享指到 B 的版本。 */
    public Mono<PrototypeVersionEntity> correlatedVersion(PrototypeShareEntity share) {
        return versions.findById(share.getVersionId())
                .filter(
                        version ->
                                share.getProjectId() != null
                                        && share.getProjectId().equals(version.getProjectId()))
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("share")));
    }

    public Flux<ProjectResponse> readableProjects() {
        return current()
                .flatMapMany(
                        actor -> {
                            String role =
                                    actor.administrator()
                                            ? "'admin'"
                                            : "case when p.owner_id=:userId then 'owner' else"
                                                    + " m.role end";
                            String scope =
                                    actor.administrator()
                                            ? "true"
                                            : "p.owner_id ~ '[^[:space:]]' and (p.owner_id=:userId"
                                                    + " or m.role in ('editor','viewer'))";
                            String sql =
                                    "select p.*,"
                                            + role
                                            + " as project_role,not exists(select 1 from s_user u"
                                            + " where u.id=p.owner_id and u.status=1) as"
                                            + " claim_required from p_project p left join"
                                            + " p_project_member m on m.project_id=p.id and"
                                            + " m.user_id=:userId where "
                                            + scope
                                            + " order by p.create_time desc,p.id";
                            if (actor.administrator())
                                log.info(
                                        "Prototype administrator project scope query: actor={}",
                                        actor.userId());
                            // 范围、角色和待认领状态由同一 SQL 产生，不逐条再查询用户或拼接创建者过滤。
                            return database.sql(sql)
                                    .bind("userId", actor.userId())
                                    .map(
                                            (row, metadata) ->
                                                    new ProjectResponse(
                                                            row.get("id", String.class),
                                                            row.get("name", String.class),
                                                            row.get("description", String.class),
                                                            row.get("cover", String.class),
                                                            row.get("creator_id", String.class),
                                                            row.get("create_time", Long.class),
                                                            row.get("modify_time", Long.class),
                                                            row.get("owner_id", String.class),
                                                            row.get("project_role", String.class),
                                                            Boolean.TRUE.equals(
                                                                    row.get(
                                                                            "claim_required",
                                                                            Boolean.class)),
                                                            ProjectCapabilities.forRole(
                                                                    row.get(
                                                                            "project_role",
                                                                            String.class))))
                                    .all();
                        });
    }

    public Flux<PrototypeVersionEntity> readableVersions(String projectId) {
        if (projectId != null && !projectId.isBlank()) {
            return requireProject(projectId, Action.READ)
                    .thenMany(
                            versions.createQuery()
                                    .where(PrototypeVersionEntity::getProjectId, projectId)
                                    .fetch());
        }
        return current()
                .flatMapMany(
                        actor -> {
                            String scope =
                                    actor.administrator()
                                            ? "true"
                                            : "p.owner_id ~ '[^[:space:]]' and (p.owner_id=:userId"
                                                    + " or exists(select 1 from p_project_member m"
                                                    + " where m.project_id=p.id and m.user_id=:userId"
                                                    + " and m.role in ('editor','viewer')))";
                            String sql =
                                    "select v.* from p_version v join p_project p on"
                                            + " p.id=v.project_id where "
                                            + scope
                                            + " order by v.create_time desc,v.id";
                            var query = database.sql(sql);
                            if (!actor.administrator())
                                query = query.bind("userId", actor.userId());
                            if (actor.administrator())
                                log.info(
                                        "Prototype administrator version scope query: actor={}",
                                        actor.userId());
                            return query.map(
                                            (row, metadata) -> {
                                                PrototypeVersionEntity version =
                                                        new PrototypeVersionEntity();
                                                version.setId(row.get("id", String.class));
                                                version.setProjectId(
                                                        row.get("project_id", String.class));
                                                version.setVersionName(
                                                        row.get("version_name", String.class));
                                                version.setDescription(
                                                        row.get("description", String.class));
                                                version.setStoragePath(
                                                        row.get("storage_path", String.class));
                                                version.setEntryFile(
                                                        row.get("entry_file", String.class));
                                                version.setPreviewKey(
                                                        row.get("preview_key", String.class));
                                                version.setCurrentRevisionId(
                                                        row.get(
                                                                "current_revision_id",
                                                                String.class));
                                                version.setStatus(row.get("status", String.class));
                                                version.setCreatorId(
                                                        row.get("creator_id", String.class));
                                                version.setCreateTime(
                                                        row.get("create_time", Long.class));
                                                return version;
                                            })
                                    .all();
                        });
    }

    public Mono<ProjectResponse> response(Grant grant) {
        return claimRequired(grant.project())
                .map(pending -> ProjectResponse.from(grant.project(), grant.role(), pending));
    }

    public Mono<Boolean> claimRequired(PrototypeProjectEntity project) {
        if (project.getOwnerId() == null || project.getOwnerId().isBlank()) return Mono.just(true);
        return users.findById(project.getOwnerId())
                .map(user -> !Byte.valueOf((byte) 1).equals(user.getStatus()))
                .defaultIfEmpty(true);
    }

    public Mono<Void> requireAdministrator() {
        return current()
                .flatMap(actor -> actor.administrator() ? Mono.empty() : Mono.error(roleDenied()));
    }

    /** 修改入口在实际事务中先取得项目锁，再重新读取范围；上传解压阶段不持有此锁。 */
    public Mono<Void> lockProject(String id) {
        return database.sql("select id from p_project where id=:id for update")
                .bind("id", id)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("project")))
                .then();
    }

    private Mono<String> role(Actor actor, PrototypeProjectEntity project) {
        if (actor.administrator()) return Mono.just(ProjectRole.admin.name());
        if (project.getOwnerId() == null || project.getOwnerId().isBlank()) return Mono.empty();
        if (actor.userId().equals(project.getOwnerId())) return Mono.just(ProjectRole.owner.name());
        return database.sql(
                        "select role from p_project_member where project_id=:projectId and"
                                + " user_id=:userId and role in ('editor','viewer')")
                .bind("projectId", project.getId())
                .bind("userId", actor.userId())
                .map((row, metadata) -> row.get("role", String.class))
                .one();
    }

    private static boolean allows(String role, Action action) {
        ProjectCapabilities capabilities = ProjectCapabilities.forRole(role);
        return switch (action) {
            case READ -> capabilities.read();
            case EDIT -> capabilities.edit();
            case MEMBERS -> capabilities.manageMembers();
            case DELETE -> capabilities.deleteProject();
            case SHARE -> capabilities.manageShares();
        };
    }

    private static PrototypeHubException userUnavailable() {
        return PrototypeHubException.forbidden(
                "error.prototype-hub.user_unavailable", "PROTOTYPE_HUB_USER_UNAVAILABLE");
    }

    public static PrototypeHubException roleDenied() {
        return PrototypeHubException.forbidden(
                "error.prototype-hub.project_role_denied", "PROTOTYPE_HUB_PROJECT_ROLE_DENIED");
    }

    public record Actor(String userId, Authentication authentication, boolean administrator) {}

    public record Grant(PrototypeProjectEntity project, String role) {}
}
