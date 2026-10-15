package com.gaiys.proto.system.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.boot.logging.LogLevel;

/** 日志级别调整请求。日志记录器名称由路径给出，权限由日志管理控制器检查。 */
@Schema(description = "日志级别调整请求")
public record LogLevelRequest(@Schema(description = "日志级别；为空时恢复继承上级日志记录器的级别") LogLevel level) {}
