package com.gaiys.proto.system.web;

import com.gaiys.proto.system.web.request.LogLevelRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 日志级别管理接口。通过 Spring Boot LoggingSystem 读取和调整当前进程日志配置，入口保留系统日志权限。 */
@Tag(name = "日志级别")
@RestController
@RequestMapping("/logger/level")
@Authorize
@Resource(id = "system-logger", name = "系统日志")
public class LogLevelController {
    private final LoggingSystem logging = LoggingSystem.get(LoggingSystem.class.getClassLoader());

    @Operation(summary = "查询全部日志记录器")
    @GetMapping
    @QueryAction
    public Mono<List<LoggerConfiguration>> all() {
        return Mono.fromSupplier(logging::getLoggerConfigurations);
    }

    @Operation(summary = "查询日志记录器级别")
    @GetMapping("/{name}")
    @QueryAction
    public Mono<LoggerConfiguration> get(@PathVariable String name) {
        return Mono.fromSupplier(
                () -> {
                    check(name);
                    var result = logging.getLoggerConfiguration(name);
                    if (result == null)
                        throw new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.NOT_FOUND);
                    return result;
                });
    }

    @Operation(summary = "调整日志记录器级别")
    @PutMapping("/{name}")
    @SaveAction
    public Mono<Boolean> set(@PathVariable String name, @RequestBody LogLevelRequest request) {
        return Mono.fromSupplier(
                () -> {
                    check(name);
                    logging.setLogLevel(name, request.level());
                    return true;
                });
    }

    private static void check(String name) {
        if (name.length() > 255 || !name.matches("[A-Za-z0-9_.$-]+"))
            throw new IllegalArgumentException("validation.log_name_invalid");
    }
}
