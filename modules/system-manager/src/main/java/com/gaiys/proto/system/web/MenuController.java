package com.gaiys.proto.system.web;

import com.gaiys.proto.system.entity.MenuEntity;
import com.gaiys.proto.system.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.AllArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.crud.web.reactive.ReactiveServiceCrudController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 菜单管理接口。标准 CRUD 复用 hsweb，个人菜单由当前认证权限生成，所有者菜单初始化由事务导入服务维护。 */
@Tag(name = "菜单管理")
@RestController
@AllArgsConstructor
@RequestMapping("/menu")
@Authorize
@Resource(id = "menu", name = "菜单管理")
public class MenuController
        implements ReactiveServiceCrudController<MenuEntity, String>,
                ManagementQueryController<MenuEntity, String> {
    private final MenuService service;
    private final com.gaiys.proto.system.service.MenuGrantService grants;
    private final com.gaiys.proto.system.service.MenuInitializationService initialization;

    @Override
    public MenuService getService() {
        return service;
    }

    @Operation(summary = "查询当前用户菜单树")
    @GetMapping("/_own/tree")
    @Authorize(merge = false)
    public Mono<List<MenuEntity>> ownTree() {
        return Authentication.currentReactive().flatMap(grants::own);
    }

    @Operation(summary = "查询当前用户菜单树兼容入口")
    @org.springframework.web.bind.annotation.PostMapping("/user-own/tree")
    @Authorize(merge = false)
    public Mono<List<MenuEntity>> ownTreeAlias() {
        return ownTree();
    }

    @Operation(summary = "查询全部菜单树")
    @org.springframework.web.bind.annotation.PostMapping("/_all/tree")
    @org.hswebframework.web.authorization.annotation.QueryAction
    public Mono<List<MenuEntity>> allTree(
            @org.springframework.web.bind.annotation.RequestBody(required = false)
                    QueryParamEntity source) {
        return service.queryResultToTree(service.treeQuery(source));
    }

    @Operation(summary = "校验菜单功能编码可用性")
    @GetMapping("/code/_validate")
    @org.hswebframework.web.authorization.annotation.QueryAction
    public Mono<java.util.Map<String, Boolean>> code(
            @org.springframework.web.bind.annotation.RequestParam String code) {
        return service.createQuery()
                .where(MenuEntity::getCode, code)
                .count()
                .map(count -> java.util.Map.of("passed", count == 0));
    }

    /** Legacy menu initialization replaces the complete tree of one owner, under menu/save. */
    @Operation(summary = "替换指定所有者的菜单树")
    @org.springframework.web.bind.annotation.PatchMapping("/{owner}/_all")
    @org.hswebframework.web.authorization.annotation.SaveAction
    public Mono<org.hswebframework.ezorm.rdb.mapping.defaults.SaveResult> initialize(
            @org.springframework.web.bind.annotation.PathVariable String owner,
            @org.springframework.web.bind.annotation.RequestBody List<MenuEntity> input) {
        return initialization.replace(owner, input);
    }

    @Operation(summary = "读取菜单详情")
    @Override
    @GetMapping("/{id:.+}")
    @org.hswebframework.web.authorization.annotation.QueryAction
    public Mono<MenuEntity> getById(
            @org.springframework.web.bind.annotation.PathVariable String id) {
        return service.findById(id)
                .switchIfEmpty(
                        Mono.error(
                                com.gaiys.proto.system.service.support.ManagementSupport
                                        .missing()));
    }
}
