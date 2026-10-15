package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 原型分享创建请求；过期时间的有效性与密码散列由分享服务处理。 */
@Schema(description = "原型分享创建请求")
public record ShareCreateRequest(
        @Size(max = 128) @Schema(description = "可选的分享访问密码；非空白密码最多72个UTF-8字节") String password,
        @Schema(description = "分享过期时间（Unix 毫秒），为空时不限制有效期") Long expiresAt,
        @Size(max = 2000) @Schema(description = "可选的分享说明") String description) {}
