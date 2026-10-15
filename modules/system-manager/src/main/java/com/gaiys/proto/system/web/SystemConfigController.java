package com.gaiys.proto.system.web;

import com.gaiys.proto.system.service.SystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 基础配置接口。配置域与公开字段边界由配置服务维护，版本信息可匿名读取，其余动作沿用配置资源权限。 */
@Tag(name = "基础配置")
@RestController
@RequestMapping("/system")
@Authorize
@Resource(id = "system-config", name = "基础配置")
@RequiredArgsConstructor
public class SystemConfigController {
    private final SystemConfigService service;

    @Operation(summary = "读取系统版本")
    @GetMapping("/version")
    @Authorize(ignore = true)
    public Mono<Map<String, String>> version() {
        return Mono.just(Map.of("version", "1.0.0", "edition", "opensource"));
    }

    @Operation(summary = "读取指定配置域")
    @PostMapping("/config/scopes")
    @QueryAction
    public Flux<SystemConfigService.Scope> scopes(@RequestBody List<String> scopes) {
        return service.scopes(scopes);
    }

    @Operation(summary = "读取单个配置域")
    @GetMapping("/config/{scope}")
    @QueryAction
    public Mono<Map<String, Object>> scope(@PathVariable String scope) {
        return service.get(scope);
    }

    @Operation(summary = "保存多个配置域")
    @PostMapping("/config/scope/_save")
    @SaveAction
    public Mono<Void> save(@RequestBody List<SystemConfigService.Scope> scopes) {
        return service.save(scopes);
    }

    @Operation(summary = "保存单个配置域")
    @PostMapping("/config/{scope}")
    @SaveAction
    public Mono<Void> saveOne(
            @PathVariable String scope, @RequestBody Map<String, Object> properties) {
        return service.save(List.of(new SystemConfigService.Scope(scope, properties)));
    }
}
