package com.gaiys.proto.system.web;

import com.gaiys.proto.logging.query.LogQuery;
import com.gaiys.proto.logging.store.LogStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 系统日志查询接口。日志存储和筛选由日志组件维护，入口保留 hsweb 系统日志查询权限。 */
@Tag(name = "系统日志")
@RestController
@RequestMapping("/logger/system")
@Authorize
@Resource(id = "system-logger", name = "系统日志")
public class SystemLogController {
    private final LogStore store;

    public SystemLogController(LogStore store) {
        this.store = store;
    }

    @Operation(summary = "分页查询系统日志")
    @PostMapping("/_query")
    @QueryAction
    public Mono<LogQuery.Page> query(@RequestBody LogQuery query) {
        return Mono.defer(() -> store.query("system", query));
    }
}
