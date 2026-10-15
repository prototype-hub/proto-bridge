package com.gaiys.proto.system.service.bridge;

import com.gaiys.proto.system.entity.OrganizationEntity;
import com.gaiys.proto.system.entity.PositionEntity;
import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.crud.events.EntityCreatedEvent;
import org.hswebframework.web.crud.events.EntityDeletedEvent;
import org.hswebframework.web.crud.events.EntityModifyEvent;
import org.hswebframework.web.crud.events.EntitySavedEvent;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.api.event.ClearUserAuthorizationCacheEvent;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 组织与职位实体事件的授权同步桥接器。沿用 hsweb 维度服务维护关联信息和缓存失效，事件处理保持响应式执行。 */
@Component
@RequiredArgsConstructor
public class OrganizationAuthorizationBridge {
    private final DefaultDimensionService dimensions;
    private final org.hswebframework.web.system.authorization.defaults.service
                    .DefaultDimensionUserService
            bindings;
    private final ApplicationEventPublisher publisher;

    private Mono<Void> sync(String type, String id, String name, String parent) {
        DimensionEntity d = new DimensionEntity();
        d.setId(id);
        d.setTypeId(type);
        d.setName(name);
        d.setParentId(parent);
        return dimensions
                .findById(id)
                .flatMap(
                        old ->
                                type.equals(old.getTypeId())
                                        ? Mono.empty()
                                        : Mono.error(ManagementSupport.conflict()))
                .then(dimensions.save(d))
                .then(
                        bindings.createUpdate()
                                .set(
                                        org.hswebframework.web.system.authorization.api.entity
                                                        .DimensionUserEntity
                                                ::getDimensionName,
                                        name)
                                .where(
                                        org.hswebframework.web.system.authorization.api.entity
                                                        .DimensionUserEntity
                                                ::getDimensionTypeId,
                                        type)
                                .and(
                                        org.hswebframework.web.system.authorization.api.entity
                                                        .DimensionUserEntity
                                                ::getDimensionId,
                                        id)
                                .execute())
                .then(ClearUserAuthorizationCacheEvent.all().publish(publisher));
    }

    private Mono<Void> org(List<OrganizationEntity> rows) {
        return Flux.fromIterable(rows)
                .concatMap(e -> sync("org", e.getId(), e.getName(), e.getParentId()))
                .then();
    }

    private Mono<Void> position(List<PositionEntity> rows) {
        return Flux.fromIterable(rows)
                .concatMap(e -> sync("position", e.getId(), e.getName(), e.getParentId()))
                .then();
    }

    @EventListener
    public void orgCreated(EntityCreatedEvent<OrganizationEntity> e) {
        e.async(org(e.getEntity()));
    }

    @EventListener
    public void orgSaved(EntitySavedEvent<OrganizationEntity> e) {
        e.async(org(e.getEntity()));
    }

    @EventListener
    public void orgUpdated(EntityModifyEvent<OrganizationEntity> e) {
        e.async(org(e.getAfter()));
    }

    @EventListener
    public void positionCreated(EntityCreatedEvent<PositionEntity> e) {
        e.async(position(e.getEntity()));
    }

    @EventListener
    public void positionSaved(EntitySavedEvent<PositionEntity> e) {
        e.async(position(e.getEntity()));
    }

    @EventListener
    public void positionUpdated(EntityModifyEvent<PositionEntity> e) {
        e.async(position(e.getAfter()));
    }

    @EventListener
    public void orgDeleted(EntityDeletedEvent<OrganizationEntity> e) {
        e.async(
                dimensions
                        .deleteById(Flux.fromIterable(e.getEntity()).map(OrganizationEntity::getId))
                        .then());
    }

    @EventListener
    public void positionDeleted(EntityDeletedEvent<PositionEntity> e) {
        e.async(
                dimensions
                        .deleteById(Flux.fromIterable(e.getEntity()).map(PositionEntity::getId))
                        .then(ClearUserAuthorizationCacheEvent.all().publish(publisher)));
    }
}
