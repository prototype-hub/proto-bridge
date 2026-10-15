package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 原型项目创建和更新请求；字段校验由 Web 入口统一执行。 */
@Schema(description = "原型项目创建和更新请求")
public record ProjectRequest(
        @NotBlank @Size(max = 255) @Schema(description = "原型项目名称") String name,
        @Size(max = 2000) @Schema(description = "原型项目说明") String description,
        @Size(max = 1024) @Schema(description = "原型项目封面地址") String cover) {}
