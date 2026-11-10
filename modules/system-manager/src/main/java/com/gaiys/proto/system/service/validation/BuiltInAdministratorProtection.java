package com.gaiys.proto.system.service.validation;

import com.gaiys.proto.system.service.BuiltInAdministratorService;
import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.crud.events.EntityBeforeDeleteEvent;
import org.hswebframework.web.crud.events.EntityBeforeSaveEvent;
import org.hswebframework.web.crud.events.EntityEventListenerConfigure;
import org.hswebframework.web.crud.events.EntityEventListenerCustomizer;
import org.hswebframework.web.crud.events.EntityEventPhase;
import org.hswebframework.web.crud.events.EntityEventType;
import org.hswebframework.web.crud.events.EntityPrepareModifyEvent;
import org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionUserService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultReactiveUserService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 在实体写入前统一保护内置账号，覆盖业务入口、官方 CRUD 和批量 DSL。 */
@Component
@RequiredArgsConstructor
public class BuiltInAdministratorProtection implements EntityEventListenerCustomizer {
    // 事件配置早于 Repository 初始化；实际写入时才获取服务，避免构造循环。
    private final ObjectProvider<BuiltInAdministratorService> administrators;
    private final ObjectProvider<DefaultReactiveUserService> users;
    private final ObjectProvider<DefaultDimensionUserService> bindings;

    @Override
    public void customize(EntityEventListenerConfigure configure) {
        configure.enable(UserEntity.class, EntityEventType.save, EntityEventPhase.before);
        configure.enable(UserEntity.class, EntityEventType.modify, EntityEventPhase.prepare);
        configure.enable(UserEntity.class, EntityEventType.delete, EntityEventPhase.before);
    }

    @EventListener
    public void saveUser(EntityBeforeSaveEvent<UserEntity> event) {
        List<String> ids =
                event.getEntity().stream().map(UserEntity::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return;
        event.async(
                users.getObject()
                        .findById(ids)
                        .collectMap(UserEntity::getId)
                        .doOnNext(
                                before ->
                                        event.getEntity()
                                                .forEach(
                                                        row -> {
                                                            UserEntity current =
                                                                    before.get(row.getId());
                                                            if (current != null)
                                                                administrators
                                                                        .getObject()
                                                                        .validateUpdate(
                                                                                current, row);
                                                        }))
                        .then());
    }

    @EventListener
    public void modifyUser(EntityPrepareModifyEvent<UserEntity> event) {
        for (int i = 0; i < event.getBefore().size(); i++)
            administrators
                    .getObject()
                    .validateReplacement(event.getBefore().get(i), event.getAfter().get(i));
    }

    @EventListener
    public void deleteUser(EntityBeforeDeleteEvent<UserEntity> event) {
        if (event.getEntity().stream().anyMatch(administrators.getObject()::isConfigured))
            event.async(Mono.error(ManagementSupport.conflict()));
    }

    @EventListener
    public void saveBinding(EntityBeforeSaveEvent<DimensionUserEntity> event) {
        List<String> ids =
                event.getEntity().stream()
                        .map(DimensionUserEntity::getId)
                        .filter(Objects::nonNull)
                        .toList();
        if (ids.isEmpty()) return;
        event.async(
                bindings.getObject()
                        .findById(ids)
                        .collectMap(DimensionUserEntity::getId)
                        .flatMap(
                                before ->
                                        validateBindings(
                                                event.getEntity().stream()
                                                        .map(row -> before.get(row.getId()))
                                                        .toList(),
                                                event.getEntity(),
                                                false)));
    }

    @EventListener
    public void modifyBinding(EntityPrepareModifyEvent<DimensionUserEntity> event) {
        event.async(validateBindings(event.getBefore(), event.getAfter(), true));
    }

    private Mono<Void> validateBindings(
            List<DimensionUserEntity> before, List<DimensionUserEntity> after, boolean complete) {
        return administrators
                .getObject()
                .find()
                .doOnNext(
                        user -> {
                            for (int i = 0; i < after.size(); i++) {
                                DimensionUserEntity row = after.get(i);
                                DimensionUserEntity previous = before.get(i);
                                if (protectedBinding(previous, user.getId())
                                        && (((complete || row.getId() != null)
                                                        && !previous.getId().equals(row.getId()))
                                                || ((complete || row.getUserId() != null)
                                                        && !user.getId().equals(row.getUserId()))
                                                || ((complete || row.getDimensionId() != null)
                                                        && !"ADMIN".equals(row.getDimensionId()))
                                                || ((complete || row.getDimensionTypeId() != null)
                                                        && !"role"
                                                                .equals(row.getDimensionTypeId()))))
                                    throw ManagementSupport.conflict();
                            }
                        })
                .then();
    }

    @EventListener
    public void deleteBinding(EntityBeforeDeleteEvent<DimensionUserEntity> event) {
        event.async(
                administrators
                        .getObject()
                        .find()
                        .doOnNext(
                                user -> {
                                    if (event.getEntity().stream()
                                            .anyMatch(row -> protectedBinding(row, user.getId())))
                                        throw ManagementSupport.conflict();
                                })
                        .then());
    }

    private boolean protectedBinding(DimensionUserEntity row, String userId) {
        return row != null
                && userId.equals(row.getUserId())
                && "role".equals(row.getDimensionTypeId())
                && "ADMIN".equals(row.getDimensionId());
    }
}
