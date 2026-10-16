package com.gaiys.proto.system.web;

import com.gaiys.proto.system.enums.UserType;
import com.gaiys.proto.system.service.SystemManagementService;
import com.gaiys.proto.system.service.support.ManagementSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 用户管理接口。资源动作权限由 hsweb 入口检查，账号、资料与维度绑定由系统管理服务统一编排。 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/user")
@Authorize
@Resource(id = "user", name = "用户管理")
@RequiredArgsConstructor
public class UserManagementController {
    private final SystemManagementService service;

    @Operation(summary = "分页查询用户详情")
    @PostMapping("/detail/_query")
    @QueryAction
    public Mono<PagerResult<Map<String, Object>>> page(@RequestBody QueryParamEntity query) {
        return service.userPage(query);
    }

    @Operation(summary = "分页查询用户")
    @PostMapping("/_query/")
    @QueryAction
    public Mono<PagerResult<Map<String, Object>>> pageAlias(@RequestBody QueryParamEntity query) {
        return page(query);
    }

    @Operation(summary = "读取用户详情")
    @GetMapping("/detail/{id}")
    @QueryAction
    public Mono<Map<String, Object>> get(@PathVariable String id) {
        return service.user(id);
    }

    @Operation(summary = "创建用户及资料")
    @PostMapping("/detail/_create")
    @SaveAction
    public Mono<Map<String, Object>> create(@RequestBody Map<String, Object> input) {
        return service.saveUser(input, null, true);
    }

    @Operation(summary = "更新用户及资料")
    @PutMapping("/detail/{id}/_update")
    @SaveAction
    public Mono<Map<String, Object>> update(
            @PathVariable String id, @RequestBody Map<String, Object> input) {
        return service.saveUser(input, id, false);
    }

    @Operation(summary = "重置用户密码")
    @PostMapping("/{id}/password/_reset")
    @SaveAction
    public Mono<Void> password(@PathVariable String id, @RequestBody String password) {
        return service.resetPassword(id, password);
    }

    @Operation(summary = "校验用户名可用性")
    @PostMapping("/username/_validate")
    @QueryAction
    public Mono<Map<String, Object>> username(@RequestBody String username) {
        return service.validateUsername(username);
    }

    @Operation(summary = "查询用户类型")
    @GetMapping("/detail/types")
    @QueryAction
    public Mono<List<UserType>> types() {
        return Mono.just(List.of(UserType.values()));
    }

    @Operation(summary = "替换用户维度绑定")
    @PutMapping("/detail/{id}/{type}/_bind")
    @SaveAction
    @org.springframework.transaction.annotation.Transactional
    public Mono<Void> fullBind(
            @PathVariable String id, @PathVariable String type, @RequestBody List<String> targets) {
        if (!java.util.Set.of("role", "org", "position").contains(type))
            return Mono.error(ManagementSupport.invalid());
        return service.user(id).then(service.replaceBindings(id, type, targets));
    }

    @Operation(summary = "分页查询维度下用户")
    @PostMapping("/detail/{type}/{id}/_query")
    @QueryAction
    public Mono<PagerResult<Map<String, Object>>> dimensionUsers(
            @PathVariable String type,
            @PathVariable String id,
            @RequestBody QueryParamEntity query) {
        if (!java.util.Set.of("role", "org", "position").contains(type))
            return Mono.error(ManagementSupport.invalid());
        var term = new org.hswebframework.ezorm.core.param.Term();
        term.setColumn("id$in-dimension$" + type);
        term.setValue(id);
        term.setTermType("eq");
        var group = new org.hswebframework.ezorm.core.param.Term();
        group.setTerms(new java.util.ArrayList<>(query.getTerms()));
        query.setTerms(new java.util.ArrayList<>(List.of(group, term)));
        return service.dimension(type, id).then(service.userPage(query));
    }
}
