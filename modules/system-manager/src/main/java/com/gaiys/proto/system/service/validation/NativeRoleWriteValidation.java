package com.gaiys.proto.system.service.validation;

import com.gaiys.proto.system.service.RoleGroupService;
import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.crud.events.EntityCreatedEvent;
import org.hswebframework.web.crud.events.EntityModifyEvent;
import org.hswebframework.web.crud.events.EntityPrepareCreateEvent;
import org.hswebframework.web.crud.events.EntityPrepareModifyEvent;
import org.hswebframework.web.crud.events.EntityPrepareSaveEvent;
import org.hswebframework.web.crud.events.EntitySavedEvent;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity;
import org.hswebframework.web.system.authorization.api.event.ClearUserAuthorizationCacheEvent;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionUserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 官方维度写入入口与业务角色入口共用状态、分组、类型冲突和绑定名称一致性规则。 */
@Component
@RequiredArgsConstructor
public class NativeRoleWriteValidation {
    private final DefaultDimensionService dimensions;
    private final DefaultDimensionUserService bindings;
    private final RoleGroupService groups;
    private final ApplicationEventPublisher events;

    @EventListener
    public void prepareCreate(EntityPrepareCreateEvent<DimensionEntity> event) {
        event.async(validate(event.getEntity()));
    }

    @EventListener
    public void prepareSave(EntityPrepareSaveEvent<DimensionEntity> event) {
        // 树服务的 PUT 复用 SAVE；Prepare 必须先归一化实体，再由 upsert 捕获属性 Map。
        event.async(validate(event.getEntity()));
    }

    @EventListener
    public void prepareModify(EntityPrepareModifyEvent<DimensionEntity> event) {
        // DSL 修改按合成的 after 校验角色规则；原生树服务的 PUT 归一化由 PrepareSave 处理。
        event.async(validate(event.getAfter()));
    }

    private Mono<Void> validate(List<DimensionEntity> rows) {
        List<String> ids = ids(rows);
        Mono<Map<String, DimensionEntity>> existing =
                ids.isEmpty()
                        ? Mono.just(Map.of())
                        : dimensions.findById(ids).collectMap(DimensionEntity::getId);
        // 修改事件可能只有局部字段；持久化类型与属性才是保护内置角色的权威依据。
        return existing.flatMapMany(
                        current ->
                                Flux.fromIterable(rows)
                                        .concatMap(
                                                row ->
                                                        role(
                                                                row,
                                                                row.getId() == null
                                                                        ? null
                                                                        : current.get(
                                                                                row.getId()))))
                .then();
    }

    private Mono<Void> role(DimensionEntity row, DimensionEntity before) {
        String previousType = before == null ? null : before.getTypeId();
        String nextType = row.getTypeId() == null ? previousType : row.getTypeId();
        if (before != null
                && ("role".equals(previousType) || "role".equals(nextType))
                && !Objects.equals(previousType, nextType))
            return Mono.error(ManagementSupport.conflict());
        if (!"role".equals(nextType)) return Mono.empty();

        Map<String, Object> properties = new LinkedHashMap<>();
        if (before != null && before.getProperties() != null)
            properties.putAll(before.getProperties());
        if (row.getProperties() != null) properties.putAll(row.getProperties());
        Object state = properties.getOrDefault("state", "enabled");
        if (!(state instanceof String text) || !Set.of("enabled", "disabled").contains(text))
            return Mono.error(ManagementSupport.invalid());
        if (("ADMIN".equals(row.getId()) || "MEMBER".equals(row.getId()))
                && "disabled".equals(state)) return Mono.error(ManagementSupport.conflict());
        Object group = properties.get("groupId");
        if (group != null && !(group instanceof String))
            return Mono.error(ManagementSupport.invalid());
        String groupId = group == null ? "default" : (String) group;
        if (groupId.isBlank() || "default_group".equals(groupId)) groupId = "default";
        properties.put("groupId", groupId);
        properties.put("state", state);
        row.setTypeId("role");
        row.setProperties(properties);
        return groups.findById(groupId)
                .switchIfEmpty(Mono.error(ManagementSupport.missing()))
                .then();
    }

    @EventListener
    public void created(EntityCreatedEvent<DimensionEntity> event) {
        event.async(synchronizeNames(event.getEntity()));
    }

    @EventListener
    public void saved(EntitySavedEvent<DimensionEntity> event) {
        event.async(synchronizeNames(event.getEntity()));
    }

    @EventListener
    public void modified(EntityModifyEvent<DimensionEntity> event) {
        event.async(synchronizeNames(event.getAfter()));
    }

    private Mono<Void> synchronizeNames(List<DimensionEntity> rows) {
        List<String> ids = ids(rows);
        if (ids.isEmpty()) return Mono.empty();
        // 从刚写入的角色读取实际名称，局部 PUT 不会把遗漏的名称同步为 null。
        return dimensions
                .findById(ids)
                .filter(row -> "role".equals(row.getTypeId()))
                .collectList()
                .flatMap(
                        roles -> {
                            if (roles.isEmpty()) return Mono.empty();
                            return Flux.fromIterable(roles)
                                    .concatMap(
                                            row ->
                                                    bindings.createUpdate()
                                                            .set(
                                                                    DimensionUserEntity
                                                                            ::getDimensionName,
                                                                    row.getName())
                                                            .where(
                                                                    DimensionUserEntity
                                                                            ::getDimensionTypeId,
                                                                    "role")
                                                            .and(
                                                                    DimensionUserEntity
                                                                            ::getDimensionId,
                                                                    row.getId())
                                                            .execute())
                                    .then(ClearUserAuthorizationCacheEvent.all().publish(events));
                        });
    }

    private List<String> ids(List<DimensionEntity> rows) {
        return rows.stream()
                .map(DimensionEntity::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
