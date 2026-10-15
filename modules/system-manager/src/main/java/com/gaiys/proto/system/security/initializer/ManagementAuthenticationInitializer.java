package com.gaiys.proto.system.security.initializer;

import java.util.List;
import org.hswebframework.web.authorization.Dimension;
import org.hswebframework.web.system.authorization.api.entity.AuthorizationSettingEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultAuthorizationSettingService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultPermissionService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultReactiveAuthenticationInitializeService;
import reactor.core.publisher.Flux;

/** Retains hsweb authorization merging while excluding disabled management roles. */
public class ManagementAuthenticationInitializer
        extends DefaultReactiveAuthenticationInitializeService {
    private final DefaultDimensionService dimensions;
    private final DefaultAuthorizationSettingService settings;
    private final DefaultPermissionService permissions;

    public ManagementAuthenticationInitializer(
            DefaultDimensionService dimensions,
            DefaultAuthorizationSettingService settings,
            DefaultPermissionService permissions) {
        this.dimensions = dimensions;
        this.settings = settings;
        this.permissions = permissions;
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
    protected reactor.core.publisher.Mono<
                    java.util.Map<
                            String,
                            org.hswebframework.web.system.authorization.api.entity
                                    .PermissionEntity>>
            getAllPermission() {
        return permissions
                .createQuery()
                .where(
                        org.hswebframework.web.system.authorization.api.entity.PermissionEntity
                                ::getStatus,
                        1)
                .fetch()
                .collectMap(
                        org.hswebframework.web.system.authorization.api.entity.PermissionEntity
                                ::getId);
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
