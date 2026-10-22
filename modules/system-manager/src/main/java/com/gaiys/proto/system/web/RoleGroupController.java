package com.gaiys.proto.system.web;

import com.gaiys.proto.system.entity.RoleGroupEntity;
import com.gaiys.proto.system.service.RoleGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.rdb.supports.json.JsonTermType;
import org.hswebframework.ezorm.rdb.supports.json.JsonValueCondition;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.DeleteAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.crud.web.reactive.ReactiveServiceCrudController;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 角色分组接口。标准 CRUD 复用 hsweb，删除保留默认分组及已有角色和子分组的依赖约束。 */
@Tag(name = "角色分组")
@RestController
@RequestMapping("/role/group")
@Authorize
@Resource(id = "role", name = "角色管理")
@AllArgsConstructor
public class RoleGroupController
        implements ReactiveServiceCrudController<RoleGroupEntity, String>,
                ManagementQueryController<RoleGroupEntity, String> {
    private final RoleGroupService service;

    private final DefaultDimensionService dimensions;

    @Operation(summary = "删除角色分组")
    @Override
    @DeleteMapping("/{id:.+}")
    @DeleteAction
    public reactor.core.publisher.Mono<RoleGroupEntity> delete(@PathVariable String id) {
        if ("default".equals(id))
            return reactor.core.publisher.Mono.error(
                    com.gaiys.proto.system.service.support.ManagementSupport.conflict());
        return dimensions
                .createQuery()
                .where(DimensionEntity::getTypeId, "role")
                .accept(
                        DimensionEntity::getProperties,
                        JsonTermType.value,
                        JsonValueCondition.of("groupId", id))
                .count()
                .zipWith(service.createQuery().where(RoleGroupEntity::getParentId, id).count())
                .flatMap(
                        counts -> {
                            if (counts.getT1() + counts.getT2() > 0)
                                return reactor.core.publisher.Mono.error(
                                        com.gaiys.proto.system.service.support.ManagementSupport
                                                .conflict());
                            return service.findById(id)
                                    .switchIfEmpty(
                                            reactor.core.publisher.Mono.error(
                                                    com.gaiys.proto.system.service.support
                                                            .ManagementSupport.missing()))
                                    .flatMap(row -> service.deleteById(id).thenReturn(row));
                        });
    }

    @Override
    public RoleGroupService getService() {
        return service;
    }
}
