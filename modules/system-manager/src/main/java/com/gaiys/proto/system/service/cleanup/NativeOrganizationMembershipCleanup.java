package com.gaiys.proto.system.service.cleanup;

import com.gaiys.proto.system.entity.PositionEntity;
import com.gaiys.proto.system.service.PositionService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.crud.events.EntityDeletedEvent;
import org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionUserService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 组织成员解除后清理同组织职位成员，覆盖官方原生解绑及删除入口。 */
@Component
@RequiredArgsConstructor
public class NativeOrganizationMembershipCleanup {
    private final PositionService positions;
    private final DefaultDimensionUserService bindings;

    @EventListener
    public void deleted(EntityDeletedEvent<DimensionUserEntity> event) {
        // 只处理框架实际删除的 org 行；清理产生的 position 事件不会再次进入组织清理。
        // after 仍在 NativeDimensionUserService 的整个删除事务中，失败向入口传播并回滚。
        event.async(cleanup(event.getEntity()));
    }

    private Mono<Void> cleanup(List<DimensionUserEntity> deleted) {
        Map<String, Set<String>> organizationUsers =
                deleted.stream()
                        .filter(row -> "org".equals(row.getDimensionTypeId()))
                        .collect(
                                Collectors.groupingBy(
                                        DimensionUserEntity::getDimensionId,
                                        Collectors.mapping(
                                                DimensionUserEntity::getUserId,
                                                Collectors.toSet())));
        if (organizationUsers.isEmpty()) return Mono.empty();
        return positions
                .createQuery()
                .in(PositionEntity::getOrgId, organizationUsers.keySet())
                .fetch()
                .collectMultimap(PositionEntity::getOrgId, PositionEntity::getId)
                .flatMapMany(
                        found ->
                                Flux.fromIterable(organizationUsers.entrySet())
                                        .concatMap(
                                                entry ->
                                                        remove(
                                                                found.get(entry.getKey()),
                                                                entry.getValue())))
                .then();
    }

    private Mono<Void> remove(Collection<String> positionIds, Set<String> userIds) {
        if (positionIds == null || positionIds.isEmpty()) return Mono.empty();
        // 每个组织保留自己的 users 集合，避免合并批量删除时形成位置与用户的跨组织笛卡尔积。
        return bindings.createDelete()
                .where(DimensionUserEntity::getDimensionTypeId, "position")
                .in(DimensionUserEntity::getDimensionId, positionIds)
                .in(DimensionUserEntity::getUserId, userIds)
                .execute()
                .then();
    }
}
