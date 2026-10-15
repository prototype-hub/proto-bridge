package com.gaiys.proto.system.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.system.authorization.defaults.service.DefaultPermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 权限管理兼容接口。查询和标识校验委托 hsweb 官方权限服务，资源动作权限沿用权限管理入口。 */
@Tag(name = "权限管理")
@RestController
@RequestMapping("/permission")
@Authorize
@Resource(id = "permission", name = "权限管理")
@RequiredArgsConstructor
public class PermissionCompatibilityController {
    private final DefaultPermissionService permissions;

    @Operation(summary = "校验权限标识可用性")
    @GetMapping("/id/_validate")
    @QueryAction
    public Mono<Map<String, Boolean>> validate(@RequestParam String id) {
        return permissions.findById(id).hasElement().map(exists -> Map.of("passed", !exists));
    }

    @Operation(summary = "分页查询权限")
    @PostMapping("/_query/")
    @QueryAction
    public Mono<
                    org.hswebframework.web.api.crud.entity.PagerResult<
                            org.hswebframework.web.system.authorization.api.entity
                                    .PermissionEntity>>
            page(@RequestBody org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
        com.gaiys.proto.system.service.support.ManagementSupport.limit(query);
        return permissions.queryPager(query);
    }
}
