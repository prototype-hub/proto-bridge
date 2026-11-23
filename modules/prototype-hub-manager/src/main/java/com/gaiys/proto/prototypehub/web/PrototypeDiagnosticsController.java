package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeDiagnosticsService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService;
import com.gaiys.proto.prototypehub.web.response.PrototypeDiagnosticsSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 独立query动作与当前有效ADMIN交集；普通项目权限不能读取实例级摘要。 */
@RestController
@Authorize
@Resource(id = "prototype-hub-diagnostics", name = "本地运行诊断")
@RequestMapping("/prototype-hub/diagnostics")
@Tag(name = "本地运行诊断")
@RequiredArgsConstructor
public class PrototypeDiagnosticsController {
    private final PrototypeDiagnosticsService diagnostics;
    private final PrototypeProjectAccessService access;

    @GetMapping
    @QueryAction
    @Operation(summary = "读取脱敏的本地运行快照，不上传遥测或执行修复")
    public Mono<PrototypeDiagnosticsSnapshot> current(ServerHttpResponse response) {
        response.getHeaders().setCacheControl("no-store");
        // 分项可能等待三秒，返回前再核当前账号、ADMIN绑定和query，撤权时不返回旧摘要。
        return requireCurrentQuery()
                .then(Mono.defer(diagnostics::snapshot))
                .delayUntil(snapshot -> requireCurrentQuery());
    }

    private Mono<Void> requireCurrentQuery() {
        return access.current()
                .flatMap(actor -> access.actor(actor.userId()))
                .flatMap(
                        actor ->
                                actor.administrator()
                                                && actor.authentication()
                                                        .hasPermission(
                                                                "prototype-hub-diagnostics",
                                                                "query")
                                        ? Mono.empty()
                                        : Mono.error(PrototypeProjectAccessService.roleDenied()));
    }
}
