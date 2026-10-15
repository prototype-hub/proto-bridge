package com.gaiys.proto.system.web;

import com.gaiys.proto.system.entity.MenuEntity;
import com.gaiys.proto.system.service.MenuGrantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 角色菜单授权接口。入口保留菜单查询和授权动作，授权范围与当前操作者权限由菜单授权服务维护。 */
@Tag(name = "菜单授权")
@RestController
@RequestMapping("/menu/role")
@Authorize
@Resource(id = "menu", name = "菜单管理")
@RequiredArgsConstructor
public class MenuGrantController {
    private final MenuGrantService service;

    @Operation(summary = "读取角色菜单授权树")
    @GetMapping("/{id}/_grant/tree")
    @QueryAction
    public Mono<List<MenuEntity>> tree(@PathVariable String id, QueryParamEntity query) {
        return service.roleTree(id, query);
    }

    @Operation(summary = "保存角色菜单及按钮授权")
    @PutMapping("/{id}/_grant")
    @ResourceAction(id = "grant", name = "授权")
    public Mono<Void> grant(
            @PathVariable String id, @RequestBody Map<String, List<Map<String, Object>>> request) {
        return service.grant(id, request.getOrDefault("menus", List.of()));
    }
}
