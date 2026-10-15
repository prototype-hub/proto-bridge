package com.gaiys.proto.system.service.provider;

import com.gaiys.proto.system.service.PositionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.DefaultDimensionType;
import org.hswebframework.web.authorization.Dimension;
import org.hswebframework.web.authorization.DimensionProvider;
import org.hswebframework.web.authorization.DimensionType;
import org.hswebframework.web.system.authorization.api.entity.DimensionUserEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionUserService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** hsweb 职位维度授权提供方。只从用户已有职位绑定扩展上级职位、组织和角色，不注册或管理官方维度类型。 */
@Component
@RequiredArgsConstructor
public class PositionRoleProvider implements DimensionProvider {
    private final DefaultDimensionService dimensions;
    private final DefaultDimensionUserService bindings;
    private final PositionService positions;

    /** 类型注册由官方提供方负责；这里仅补充用户绑定所派生的维度。 */
    @Override
    public Flux<DimensionType> getAllType() {
        return Flux.empty();
    }

    /** 根据已有职位绑定派生用户维度，空绑定返回空流；查询错误向授权链路传播。 上级职位扩展沿用现有 200 条上限，避免收集无界层级数据。 */
    @Override
    public Flux<? extends Dimension> getDimensionByUserId(String userId) {
        return bindings.createQuery()
                .where(DimensionUserEntity::getUserId, userId)
                .and(DimensionUserEntity::getDimensionTypeId, "position")
                .fetch()
                .map(DimensionUserEntity::getDimensionId)
                .collectList()
                .filter(ids -> !ids.isEmpty())
                .flatMapMany(positions::findById)
                .expand(
                        p ->
                                p.getParentId() == null || p.getParentId().isBlank()
                                        ? Mono.empty()
                                        : positions.findById(p.getParentId()))
                .distinct(com.gaiys.proto.system.entity.PositionEntity::getId)
                .take(200)
                .collectList()
                .filter(rows -> !rows.isEmpty())
                .flatMapMany(
                        rows -> {
                            List<String> roleIds =
                                    rows.stream()
                                            .flatMap(
                                                    p ->
                                                            p.getRoles() == null
                                                                    ? java.util.stream.Stream
                                                                            .empty()
                                                                    : p.getRoles().stream())
                                            .map(role -> role.get("id"))
                                            .distinct()
                                            .toList();
                            List<String> orgIds =
                                    rows.stream().map(p -> p.getOrgId()).distinct().toList();
                            List<String> positionIds =
                                    rows.stream().map(p -> p.getId()).distinct().toList();
                            return Flux.concat(
                                    roleIds.isEmpty()
                                            ? Flux.empty()
                                            : dimensions.getDimensionsById(
                                                    DefaultDimensionType.role, roleIds),
                                    dimensions.getDimensionsById(new Type("position"), positionIds),
                                    dimensions.getDimensionsById(new Type("org"), orgIds));
                        });
    }

    private record Type(String id) implements DimensionType {
        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getName() {
            return id;
        }
    }

    /** 单个维度查找沿用官方提供方，此扩展不接管该查询。 */
    @Override
    public Mono<? extends Dimension> getDimensionById(DimensionType type, String id) {
        return Mono.empty();
    }

    /** 反向用户查询沿用官方提供方，此扩展不接管该查询。 */
    @Override
    public Flux<String> getUserIdByDimensionId(String id) {
        return Flux.empty();
    }
}
