package com.gaiys.proto.system.service.validation;

import com.gaiys.proto.system.service.PositionService;
import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.crud.events.EntityBeforeDeleteEvent;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 在官方维度删除事件边界保护内置角色和职位引用，覆盖业务及原生 CRUD 入口。 */
@Component
@RequiredArgsConstructor
public class NativeDimensionDeleteValidation {
    private final PositionService positions;

    @EventListener
    public void beforeDelete(EntityBeforeDeleteEvent<DimensionEntity> event) {
        // before-delete 的异步校验属于官方删除链；失败在任何候选行删除前终止整个操作。
        event.async(validate(event.getEntity()));
    }

    private Mono<Void> validate(List<DimensionEntity> rows) {
        Set<String> roles =
                rows.stream()
                        .filter(row -> "role".equals(row.getTypeId()))
                        .map(DimensionEntity::getId)
                        .collect(Collectors.toSet());
        if (roles.isEmpty()) return Mono.empty();
        if (roles.contains("ADMIN") || roles.contains("MEMBER"))
            return Mono.error(ManagementSupport.conflict());
        // 按既有业务入口的精确角色 ID 引用规则校验，一次查询覆盖本次完整删除候选集合。
        return positions
                .createQuery()
                .fetch()
                .any(
                        position ->
                                position.getRoles() != null
                                        && position.getRoles().stream()
                                                .anyMatch(role -> roles.contains(role.get("id"))))
                .flatMap(
                        referenced ->
                                referenced
                                        ? Mono.error(ManagementSupport.conflict())
                                        : Mono.empty());
    }
}
