package com.gaiys.proto.system.service;

import com.gaiys.proto.system.entity.RoleGroupEntity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.TransactionManagers;
import org.hswebframework.web.authorization.define.AuthorizeDefinitionCustomizer;
import org.hswebframework.web.authorization.define.AuthorizeDefinitionInitializedEvent;
import org.hswebframework.web.authorization.define.CompositeAuthorizeDefinitionCustomizer;
import org.hswebframework.web.authorization.define.MergedAuthorizeDefinition;
import org.hswebframework.web.authorization.define.ResourceDefinition;
import org.hswebframework.web.system.authorization.api.entity.AuthorizationSettingEntity;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.api.entity.DimensionTypeEntity;
import org.hswebframework.web.system.authorization.api.entity.PermissionEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultAuthorizationSettingService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultPermissionService;
import org.hswebframework.web.system.authorization.defaults.service.PermissionSynchronization;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 在 EasyORM 建表后补齐首次安装所需的账号维度和权限定义。
 *
 * <p>权限合并复用 hsweb 的公开转换规则；仅新建内置角色时写默认授权。重启不会恢复已撤销的 授权、重命名已有角色或修改用户密码，菜单仍由独立的空表初始化流程维护。
 *
 * @see PermissionSynchronization
 */
@Service
public class SystemBootstrapService {
    private static final Map<String, Set<String>> MEMBER_ACTIONS =
            Map.of(
                    "prototype-hub-project", Set.of("query"),
                    "prototype-hub-version", Set.of("query"),
                    "prototype-hub-share", Set.of("query"),
                    "user", Set.of("update-self-info", "update-self-pwd"));
    private final MergedAuthorizeDefinition definitions = new MergedAuthorizeDefinition();
    private final DatabaseClient database;
    private final ReactiveRepository<DimensionTypeEntity, String> types;
    private final DefaultDimensionService dimensions;
    private final RoleGroupService groups;
    private final DefaultPermissionService permissions;
    private final DefaultAuthorizationSettingService settings;
    private final AuthorizeDefinitionCustomizer customizer;
    private final TransactionalOperator transactions;

    public SystemBootstrapService(
            DatabaseClient database,
            ReactiveRepository<DimensionTypeEntity, String> types,
            DefaultDimensionService dimensions,
            RoleGroupService groups,
            DefaultPermissionService permissions,
            DefaultAuthorizationSettingService settings,
            ObjectProvider<AuthorizeDefinitionCustomizer> customizers,
            @Qualifier(TransactionManagers.reactiveTransactionManager)
                    ReactiveTransactionManager manager) {
        this.database = database;
        this.types = types;
        this.dimensions = dimensions;
        this.groups = groups;
        this.permissions = permissions;
        this.settings = settings;
        this.customizer = new CompositeAuthorizeDefinitionCustomizer(customizers);
        this.transactions = TransactionalOperator.create(manager);
    }

    /** 收集与 hsweb 自动同步相同的公开权限定义，不修改已有授权或自行扫描控制器。 */
    @EventListener
    public void handleResourceParseEvent(AuthorizeDefinitionInitializedEvent event) {
        definitions.merge(event.getAllDefinition());
    }

    /** 初始化失败向启动入口传播；所有种子数据在同一响应式事务中提交。 */
    public Mono<Void> initialize() {
        return Mono.defer(
                        () ->
                                database.sql(
                                                "LOCK TABLE s_dimension_type, s_role_group, s_dimension, s_autz_setting_info, s_permission IN EXCLUSIVE MODE")
                                        .fetch()
                                        .rowsUpdated()
                                        .then(synchronizePermissions())
                                        .then(initializeType("role", "角色"))
                                        .then(initializeType("org", "组织"))
                                        .then(initializeType("position", "职位"))
                                        .then(initializeDefaultGroup())
                                        .then(initializeRole("ADMIN", "管理员", 0))
                                        .then(initializeRole("MEMBER", "普通成员", 1)))
                .as(transactions::transactional);
    }

    private Mono<Void> synchronizePermissions() {
        return Mono.defer(
                () -> {
                    customizer.custom(definitions);
                    List<ResourceDefinition> resources = List.copyOf(definitions.getResources());
                    if (resources.isEmpty()) {
                        return Mono.error(
                                new IllegalStateException(
                                        "Authorization definitions must be initialized before system bootstrap"));
                    }
                    // 官方 runner 使用 subscribe 异步同步；这里先完成同样的合并，保证首个登录已可授权。
                    // convert 保留现有资源名称、状态及动作名称，不会扩充任何角色的 grant。
                    return permissions
                            .createQuery()
                            .in(
                                    PermissionEntity::getId,
                                    resources.stream().map(ResourceDefinition::getId).toList())
                            .fetch()
                            .collectMap(PermissionEntity::getId)
                            .flatMap(
                                    existing ->
                                            permissions.save(
                                                    Flux.fromIterable(resources)
                                                            .map(
                                                                    resource ->
                                                                            PermissionSynchronization
                                                                                    .convert(
                                                                                            existing,
                                                                                            resource,
                                                                                            Map
                                                                                                    .of()))))
                            .then();
                });
    }

    private Mono<Void> initializeType(String id, String name) {
        DimensionTypeEntity type = new DimensionTypeEntity();
        type.setId(id);
        type.setName(name);
        return types.findById(id)
                .hasElement()
                .flatMap(exists -> exists ? Mono.empty() : types.insert(Mono.just(type)).then());
    }

    private Mono<Void> initializeDefaultGroup() {
        RoleGroupEntity group = new RoleGroupEntity();
        group.setId("default");
        group.setName("默认分组");
        group.setSortIndex(0L);
        return groups.findById("default")
                .hasElement()
                .flatMap(exists -> exists ? Mono.empty() : groups.insert(Mono.just(group)).then());
    }

    private Mono<Void> initializeRole(String id, String name, long sortIndex) {
        return dimensions
                .findById(id)
                .hasElement()
                .flatMap(exists -> exists ? Mono.empty() : createRole(id, name, sortIndex));
    }

    private Mono<Void> createRole(String id, String name, long sortIndex) {
        DimensionEntity role = new DimensionEntity();
        role.setId(id);
        role.setTypeId("role");
        role.setName(name);
        role.setSortIndex(sortIndex);
        role.setProperties(Map.of("groupId", "default", "state", "enabled"));
        // 角色与默认授权同事务创建；已有角色即使没有授权也不能在重启时被重新授权。
        return dimensions
                .insert(Mono.just(role))
                .then(Mono.defer(() -> settings.insert(Flux.fromIterable(defaultGrants(role)))))
                .then();
    }

    private List<AuthorizationSettingEntity> defaultGrants(DimensionEntity role) {
        return definitions.getResources().stream()
                .filter(
                        resource ->
                                "ADMIN".equals(role.getId())
                                        || MEMBER_ACTIONS.containsKey(resource.getId()))
                .map(resource -> defaultGrant(role, resource))
                .filter(setting -> !setting.getActions().isEmpty())
                .toList();
    }

    private AuthorizationSettingEntity defaultGrant(
            DimensionEntity role, ResourceDefinition resource) {
        Set<String> actions = new LinkedHashSet<>(resource.getActionIds());
        if (!"ADMIN".equals(role.getId())) actions.retainAll(MEMBER_ACTIONS.get(resource.getId()));
        AuthorizationSettingEntity setting = new AuthorizationSettingEntity();
        setting.setPermission(resource.getId());
        setting.setDimensionType("role");
        setting.setDimensionTypeName("角色");
        setting.setDimensionTarget(role.getId());
        setting.setDimensionTargetName(role.getName());
        setting.setState((byte) 1);
        setting.setActions(actions);
        setting.setPriority(0);
        setting.setMerge(false);
        return setting;
    }
}
