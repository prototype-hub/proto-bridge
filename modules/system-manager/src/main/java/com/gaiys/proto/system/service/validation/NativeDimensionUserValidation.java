package com.gaiys.proto.system.service.validation;

import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.crud.events.EntityBeforeCreateEvent;
import org.hswebframework.web.crud.events.EntityBeforeModifyEvent;
import org.hswebframework.web.crud.events.EntityBeforeSaveEvent;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionUserService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultReactiveUserService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 在所有官方成员写入口执行项目受管维度的存在性和类型校验，外部维度沿用原生行为。 */
@Component
@RequiredArgsConstructor
public class NativeDimensionUserValidation {
    private static final Set<String> MANAGED_TYPES = Set.of("role", "org", "position");
    private final DefaultDimensionService dimensions;
    private final DefaultDimensionUserService bindings;
    private final DefaultReactiveUserService users;

    @EventListener
    public void beforeCreate(EntityBeforeCreateEvent<DimensionUserEntity> event) {
        event.async(
                validate(event.getEntity().stream().map(row -> membership(row, null)).toList()));
    }

    @EventListener
    public void beforeSave(EntityBeforeSaveEvent<DimensionUserEntity> event) {
        event.async(validateSaved(event.getEntity()));
    }

    @EventListener
    public void beforeModify(EntityBeforeModifyEvent<DimensionUserEntity> event) {
        // 官方 after 已包含持久化字段与 PUT/DSL patch，不能仅检查可能省略 type 的原始补丁。
        event.async(validate(event.getAfter().stream().map(row -> membership(row, null)).toList()));
    }

    private Mono<Void> validateSaved(List<DimensionUserEntity> rows) {
        if (rows.stream()
                .allMatch(
                        row ->
                                row.getDimensionTypeId() != null
                                        && row.getDimensionId() != null
                                        && row.getUserId() != null))
            return validate(rows.stream().map(row -> membership(row, null)).toList());
        // save 的空字段沿用已有值；补查省略的关联键，不改变写入 DTO、relation 或 features。
        List<String> ids =
                rows.stream()
                        .map(DimensionUserEntity::getId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        Mono<Map<String, DimensionUserEntity>> existing =
                ids.isEmpty()
                        ? Mono.just(Map.of())
                        : bindings.findById(ids).collectMap(DimensionUserEntity::getId);
        return existing.flatMap(
                before ->
                        validate(
                                rows.stream()
                                        .map(row -> membership(row, before.get(row.getId())))
                                        .toList()));
    }

    private Membership membership(DimensionUserEntity row, DimensionUserEntity before) {
        return new Membership(
                value(
                        row.getDimensionTypeId(),
                        before == null ? null : before.getDimensionTypeId()),
                value(row.getDimensionId(), before == null ? null : before.getDimensionId()),
                value(row.getUserId(), before == null ? null : before.getUserId()));
    }

    private String value(String supplied, String previous) {
        return supplied == null ? previous : supplied;
    }

    private Mono<Void> validate(List<Membership> rows) {
        List<Membership> managed =
                rows.stream()
                        .filter(row -> row.type() != null && MANAGED_TYPES.contains(row.type()))
                        .toList();
        if (managed.isEmpty()) return Mono.empty();
        if (managed.stream()
                .anyMatch(
                        row ->
                                row.dimensionId() == null
                                        || row.dimensionId().isBlank()
                                        || row.userId() == null
                                        || row.userId().isBlank()))
            return Mono.error(ManagementSupport.missing());
        Set<String> dimensionIds =
                managed.stream().map(Membership::dimensionId).collect(Collectors.toSet());
        Set<String> userIds = managed.stream().map(Membership::userId).collect(Collectors.toSet());
        // 一次查询每种资源，先校验完整 batch；任何无效行在官方 SQL 写入前终止整次操作。
        return Mono.zip(
                        dimensions.findById(dimensionIds).collectMap(DimensionEntity::getId),
                        users.findById(userIds).map(UserEntity::getId).collect(Collectors.toSet()))
                .flatMap(
                        found -> {
                            for (Membership row : managed) {
                                DimensionEntity dimension = found.getT1().get(row.dimensionId());
                                if (dimension == null
                                        || !row.type().equals(dimension.getTypeId())
                                        || !found.getT2().contains(row.userId()))
                                    return Mono.error(ManagementSupport.missing());
                            }
                            return Mono.empty();
                        });
    }

    private record Membership(String type, String dimensionId, String userId) {}
}
