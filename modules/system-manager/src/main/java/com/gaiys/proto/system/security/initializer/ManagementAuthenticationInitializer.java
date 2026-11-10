package com.gaiys.proto.system.security.initializer;

import com.gaiys.proto.system.service.BuiltInAdministratorService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.Dimension;
import org.hswebframework.web.authorization.Permission;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimplePermission;
import org.hswebframework.web.system.authorization.api.entity.ActionEntity;
import org.hswebframework.web.system.authorization.api.entity.AuthorizationSettingEntity;
import org.hswebframework.web.system.authorization.api.entity.PermissionEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultAuthorizationSettingService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultPermissionService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultReactiveAuthenticationInitializeService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Retains hsweb authorization merging while excluding disabled management roles. */
public class ManagementAuthenticationInitializer
        extends DefaultReactiveAuthenticationInitializeService {
    private final DefaultDimensionService dimensions;
    private final DefaultAuthorizationSettingService settings;
    private final DefaultPermissionService permissions;
    private final BuiltInAdministratorService administrators;

    public ManagementAuthenticationInitializer(
            DefaultDimensionService dimensions,
            DefaultAuthorizationSettingService settings,
            DefaultPermissionService permissions,
            BuiltInAdministratorService administrators) {
        this.dimensions = dimensions;
        this.settings = settings;
        this.permissions = permissions;
        this.administrators = administrators;
    }

    @Override
    protected Mono<Authentication> initPermission(SimpleAuthentication authentication) {
        // 先保留官方真实维度；零授权设置也必须进入内置账号的权限派生分支。
        return super.initPermission(authentication)
                .flatMap(
                        auth ->
                                administrators
                                        .isEnabled(auth.getUser().getId())
                                        .filter(Boolean::booleanValue)
                                        .flatMap(ignored -> getAllPermission())
                                        .doOnNext(
                                                all ->
                                                        authentication.setPermissions(
                                                                all.values().stream()
                                                                        .map(this::fullPermission)
                                                                        .toList()))
                                        .thenReturn(auth));
    }

    private Permission fullPermission(PermissionEntity row) {
        SimplePermission permission = new SimplePermission();
        permission.setId(row.getId());
        permission.setName(row.getName());
        permission.setOptions(row.getProperties());
        permission.setActions(
                row.getActions() == null
                        ? new HashSet<>()
                        : row.getActions().stream()
                                .map(ActionEntity::getAction)
                                .collect(Collectors.toSet()));
        return permission;
    }

    @Override
    protected Flux<AuthorizationSettingEntity> findSettings(String type, List<String> targets) {
        if (targets.isEmpty()) return Flux.empty();
        return settings.createQuery()
                .where(AuthorizationSettingEntity::getState, 1)
                .and(AuthorizationSettingEntity::getDimensionType, type)
                .in(AuthorizationSettingEntity::getDimensionTarget, targets)
                .fetch();
    }

    @Override
    protected Mono<Map<String, PermissionEntity>> getAllPermission() {
        return permissions
                .createQuery()
                .where(PermissionEntity::getStatus, 1)
                .fetch()
                .collectMap(PermissionEntity::getId);
    }

    @Override
    protected Flux<AuthorizationSettingEntity> getSettings(List<Dimension> values) {
        return Flux.fromIterable(values)
                .filterWhen(
                        d -> {
                            if (!"role".equals(d.getType().getId()))
                                return reactor.core.publisher.Mono.just(true);
                            return dimensions
                                    .findById(d.getId())
                                    .map(
                                            row ->
                                                    row.getProperties() == null
                                                            || !"disabled"
                                                                    .equals(
                                                                            row.getProperties()
                                                                                    .get("state")))
                                    .defaultIfEmpty(false);
                        })
                .collectList()
                .flatMapMany(super::getSettings);
    }
}
