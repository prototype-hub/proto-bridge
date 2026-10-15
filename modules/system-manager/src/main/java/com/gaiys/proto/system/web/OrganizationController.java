package com.gaiys.proto.system.web;

import com.gaiys.proto.system.entity.OrganizationEntity;
import com.gaiys.proto.system.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.DeleteAction;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.hswebframework.web.crud.web.reactive.ReactiveServiceCrudController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 组织管理接口。标准 CRUD 复用 hsweb，组织与职位用户绑定由管理服务执行，删除保留层级与职位依赖约束。 */
@Tag(name = "组织管理")
@RestController
@RequestMapping("/organization")
@Authorize
@Resource(id = "organization", name = "组织管理")
@AllArgsConstructor
public class OrganizationController
        implements ReactiveServiceCrudController<OrganizationEntity, String>,
                ManagementQueryController<OrganizationEntity, String> {
    private final OrganizationService service;
    private final com.gaiys.proto.system.service.PositionService positions;
    private final com.gaiys.proto.system.service.SystemManagementService management;

    @Operation(summary = "查询组织树")
    @GetMapping("/_all/tree")
    @QueryAction
    public reactor.core.publisher.Mono<java.util.List<OrganizationEntity>> treeGet(
            org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
        return tree(query);
    }

    @Operation(summary = "查询全部组织")
    @GetMapping("/_all")
    @QueryAction
    public reactor.core.publisher.Flux<OrganizationEntity> all(
            org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
        return service.query(query.noPaging());
    }

    @Operation(summary = "按条件查询全部组织")
    @PostMapping("/_all")
    @QueryAction
    public reactor.core.publisher.Flux<OrganizationEntity> allPost(
            @RequestBody org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
        return all(query);
    }

    @Operation(summary = "按条件查询组织树")
    @PostMapping("/_all/tree")
    @QueryAction
    public reactor.core.publisher.Mono<java.util.List<OrganizationEntity>> tree(
            @RequestBody org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
        return service.queryResultToTree(query.noPaging());
    }

    @Operation(summary = "绑定组织用户")
    @PostMapping("/{id}/users/_bind")
    @SaveAction
    public reactor.core.publisher.Mono<Void> bind(
            @PathVariable String id, @RequestBody java.util.List<String> users) {
        return management.bind("org", id, users, false);
    }

    @Operation(summary = "解绑组织用户")
    @PostMapping("/{id}/users/_unbind")
    @SaveAction
    public reactor.core.publisher.Mono<Void> unbind(
            @PathVariable String id, @RequestBody java.util.List<String> users) {
        return management.bind("org", id, users, true);
    }

    @Operation(summary = "绑定组织内职位用户")
    @PostMapping("/{orgId}/position/{id}/users/_bind")
    @SaveAction
    public reactor.core.publisher.Mono<Void> bindPosition(
            @PathVariable String orgId,
            @PathVariable String id,
            @RequestBody java.util.List<String> users) {
        return positions
                .findById(id)
                .filter(p -> orgId.equals(p.getOrgId()))
                .switchIfEmpty(
                        reactor.core.publisher.Mono.error(
                                com.gaiys.proto.system.service.support.ManagementSupport.missing()))
                .then(management.bind("position", id, users, false));
    }

    @Operation(summary = "解绑组织内职位用户")
    @PostMapping("/{orgId}/position/{id}/users/_unbind")
    @SaveAction
    public reactor.core.publisher.Mono<Void> unbindPosition(
            @PathVariable String orgId,
            @PathVariable String id,
            @RequestBody java.util.List<String> users) {
        return positions
                .findById(id)
                .filter(p -> orgId.equals(p.getOrgId()))
                .switchIfEmpty(
                        reactor.core.publisher.Mono.error(
                                com.gaiys.proto.system.service.support.ManagementSupport.missing()))
                .then(management.bind("position", id, users, true));
    }

    @Operation(summary = "删除组织")
    @Override
    @DeleteMapping("/{id:.+}")
    @DeleteAction
    public reactor.core.publisher.Mono<OrganizationEntity> delete(@PathVariable String id) {
        return service.createQuery()
                .where(OrganizationEntity::getParentId, id)
                .count()
                .zipWith(
                        positions
                                .createQuery()
                                .where(com.gaiys.proto.system.entity.PositionEntity::getOrgId, id)
                                .count())
                .flatMap(
                        counts -> {
                            if (counts.getT1() + counts.getT2() > 0)
                                return reactor.core.publisher.Mono.error(
                                        com.gaiys.proto.system.service.support.ManagementSupport
                                                .conflict());
                            return service.findById(id)
                                    .flatMap(row -> service.deleteById(id).thenReturn(row));
                        });
    }

    @Override
    public OrganizationService getService() {
        return service;
    }

    @Operation(summary = "读取组织详情")
    @Override
    @GetMapping("/{id:.+}")
    @QueryAction
    public reactor.core.publisher.Mono<OrganizationEntity> getById(@PathVariable String id) {
        return service.findById(id)
                .switchIfEmpty(
                        reactor.core.publisher.Mono.error(
                                com.gaiys.proto.system.service.support.ManagementSupport
                                        .missing()));
    }
}
