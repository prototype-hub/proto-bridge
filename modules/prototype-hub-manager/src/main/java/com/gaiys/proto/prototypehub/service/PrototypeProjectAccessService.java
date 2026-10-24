package com.gaiys.proto.prototypehub.service;

import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.enums.ProjectRole;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.query.PrototypeScopeQuery;
import com.gaiys.proto.prototypehub.web.request.PrototypeListQueryRequest;
import com.gaiys.proto.prototypehub.web.response.ProjectCapabilities;
import com.gaiys.proto.prototypehub.web.response.ProjectResponse;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.ReactiveAuthenticationManager;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.hswebframework.web.system.authorization.api.service.reactive.ReactiveUserService;
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
    private final ReactiveRepository<UserEntity, String> userRepository;
    private final ReactiveRepository<DimensionEntity, String> dimensions;
    private final ReactiveAuthenticationManager authentication;
    private final ReactiveRepository<DimensionUserEntity, String> dimensionUsers;
    private final ReactiveRepository<PrototypeProjectMemberEntity, String> members;
    private final PrototypeScopeQuery scope;

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
                                dimensionUsers
                                        .createQuery()
                                        .where(DimensionUserEntity::getDimensionTypeId, "role")
                                        .and(DimensionUserEntity::getDimensionId, "ADMIN")
                                        .and(DimensionUserEntity::getUserId, auth.getUser().getId())
                                        .count()
                                        .map(count -> count > 0))
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
     * <p>删除账号持有用户行排他锁直到其 UserDeletedEvent 清理完成。绑定查询使用同样的排他锁与删除互斥，保证事件 扫描之后不会提交遗漏的新
     * owner/member/session；普通身份查询不得隐式取得此锁，避免项目→用户倒序。
     */
    public Mono<Boolean> tryLockActiveUser(String userId) {
        if (userId == null || userId.isBlank()) return Mono.just(false);
        // 官方 forUpdate 方言实现提供跨实例排他锁；较原共享锁更强，绑定生产者仍按用户→项目顺序。
        return userRepository
                .createQuery()
                .select(UserEntity::getId)
                .where(UserEntity::getId, userId)
                .and(UserEntity::getStatus, (byte) 1)
                .forUpdate()
                .fetchOne()
                .hasElement();
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
                .filter(project -> project.getDeletedTime() == null)
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

    /** 回收站项目仅由当前所有者或有效 ADMIN 操作，不放宽普通项目入口。 */
    public Mono<Grant> requireDeletedProject(String id) {
        return current()
                .flatMap(
                        actor ->
                                projects.findById(id)
                                        .switchIfEmpty(
                                                Mono.error(
                                                        () ->
                                                                PrototypeHubException.missing(
                                                                        "project")))
                                        .flatMap(
                                                project ->
                                                        role(actor, project)
                                                                .map(
                                                                        role ->
                                                                                new Grant(
                                                                                        project,
                                                                                        role)))
                                        .switchIfEmpty(
                                                Mono.error(
                                                        () ->
                                                                PrototypeHubException.missing(
                                                                        "project")))
                                        .flatMap(
                                                grant -> {
                                                    if (!allows(grant.role(), Action.DELETE))
                                                        return Mono.error(roleDenied());
                                                    if (grant.project().getDeletedTime() == null)
                                                        return Mono.error(
                                                                PrototypeHubException
                                                                        .resourceActive());
                                                    return Mono.just(grant);
                                                }));
    }

    /** 版本恢复先重验活动父项目的编辑权限；父项目必须单独先恢复。 */
    public Mono<PrototypeVersionEntity> requireDeletedVersion(String id) {
        return versions.findById(id)
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("version")))
                .flatMap(
                        version ->
                                requireProject(version.getProjectId(), Action.EDIT)
                                        .then(
                                                Mono.defer(
                                                        () ->
                                                                version.getDeletedTime() == null
                                                                        ? Mono.error(
                                                                                PrototypeHubException
                                                                                        .resourceActive())
                                                                        : Mono.just(version))));
    }

    public Mono<PagerResult<ProjectResponse>> deletedProjects(PrototypeListQueryRequest request) {
        return current()
                .flatMap(
                        actor ->
                                scope.deletedProjects(
                                        actor.userId(), actor.administrator(), request));
    }

    public Mono<PagerResult<PrototypeVersionEntity>> deletedVersions(
            String projectId, PrototypeListQueryRequest request) {
        return current()
                .flatMap(
                        actor -> {
                            Mono<Void> parent =
                                    projectId == null || projectId.isBlank()
                                            ? Mono.empty()
                                            : requireProject(actor, projectId, Action.EDIT, true)
                                                    .then();
                            return parent.then(
                                    Mono.defer(
                                            () ->
                                                    scope.deletedVersions(
                                                            actor.userId(),
                                                            actor.administrator(),
                                                            projectId,
                                                            request)));
                        });
    }

    public Mono<PrototypeVersionEntity> requireVersion(String id, Action action) {
        return current().flatMap(actor -> requireVersion(actor, id, action, true));
    }

    public Mono<PrototypeVersionEntity> requireVersion(
            Actor actor, String id, Action action, boolean audit) {
        return versions.findById(id)
                .filter(version -> version.getDeletedTime() == null)
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
                                version.getDeletedTime() == null
                                        && share.getProjectId() != null
                                        && share.getProjectId().equals(version.getProjectId()))
                // 公开资源不经过登录授权；关联边界也必须核验父项目尚活动。
                .flatMap(
                        version ->
                                projects.findById(version.getProjectId())
                                        .filter(project -> project.getDeletedTime() == null)
                                        .map(project -> version))
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("share")));
    }

    public Flux<ProjectResponse> readableProjects() {
        return current()
                .flatMapMany(
                        actor -> {
                            if (actor.administrator())
                                log.info(
                                        "Prototype administrator project scope query: actor={}",
                                        actor.userId());
                            return scope.projects(actor.userId(), actor.administrator());
                        });
    }

    /** 分页查询复用相同活跃账号和 ADMIN 例外，项目角色只收窄已授予的查询动作。 */
    public Mono<PagerResult<ProjectResponse>> readableProjects(PrototypeListQueryRequest request) {
        return current()
                .flatMap(
                        actor -> {
                            if (actor.administrator())
                                log.info(
                                        "Prototype administrator project scope query: actor={}",
                                        actor.userId());
                            return scope.projects(actor.userId(), actor.administrator(), request);
                        });
    }

    /** 指定项目时先沿详情入口鉴权；全局查询仍在数据库按项目成员范围收窄。 */
    public Mono<PagerResult<PrototypeVersionEntity>> readableVersions(
            String projectId, PrototypeListQueryRequest request) {
        return current()
                .flatMap(
                        actor -> {
                            Mono<Void> parent =
                                    projectId == null || projectId.isBlank()
                                            ? Mono.empty()
                                            : requireProject(actor, projectId, Action.READ, true)
                                                    .then();
                            if (actor.administrator())
                                log.info(
                                        "Prototype administrator version scope query: actor={}",
                                        actor.userId());
                            return parent.then(
                                    Mono.defer(
                                            () ->
                                                    scope.versions(
                                                            actor.userId(),
                                                            actor.administrator(),
                                                            projectId,
                                                            request)));
                        });
    }

    public Flux<PrototypeVersionEntity> readableVersions(String projectId) {
        if (projectId != null && !projectId.isBlank()) {
            return requireProject(projectId, Action.READ)
                    .thenMany(
                            versions.createQuery()
                                    .where(PrototypeVersionEntity::getProjectId, projectId)
                                    .isNull(PrototypeVersionEntity::getDeletedTime)
                                    .fetch());
        }
        return current()
                .flatMapMany(
                        actor -> {
                            if (actor.administrator())
                                log.info(
                                        "Prototype administrator version scope query: actor={}",
                                        actor.userId());
                            return scope.versions(actor.userId(), actor.administrator());
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
        return tryLockProject(id)
                .flatMap(
                        exists ->
                                exists
                                        ? Mono.empty()
                                        : Mono.error(PrototypeHubException.missing("project")));
    }

    /** 创建重放需要区分已删除父资源；同样使用实际事务连接持锁。 */
    public Mono<Boolean> tryLockProject(String id) {
        return projects.createQuery()
                .select(PrototypeProjectEntity::getId)
                .where(PrototypeProjectEntity::getId, id)
                .forUpdate()
                .fetchOne()
                .hasElement();
    }

    public Mono<Void> lockVersion(String id) {
        return versions.createQuery()
                .select(PrototypeVersionEntity::getId)
                .where(PrototypeVersionEntity::getId, id)
                .forUpdate()
                .fetchOne()
                .switchIfEmpty(Mono.error(() -> PrototypeHubException.missing("version")))
                .then();
    }

    private Mono<String> role(Actor actor, PrototypeProjectEntity project) {
        if (actor.administrator()) return Mono.just(ProjectRole.admin.name());
        if (project.getOwnerId() == null || project.getOwnerId().isBlank()) return Mono.empty();
        if (actor.userId().equals(project.getOwnerId())) return Mono.just(ProjectRole.owner.name());
        return members.createQuery()
                .select(PrototypeProjectMemberEntity::getRole)
                .where(PrototypeProjectMemberEntity::getProjectId, project.getId())
                .and(PrototypeProjectMemberEntity::getUserId, actor.userId())
                .in(PrototypeProjectMemberEntity::getRole, "editor", "viewer")
                .fetchOne()
                .map(PrototypeProjectMemberEntity::getRole);
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
