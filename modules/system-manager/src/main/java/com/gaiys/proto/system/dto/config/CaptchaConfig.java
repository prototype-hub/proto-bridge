package com.gaiys.proto.system.dto.config;

import io.swagger.v3.oas.annotations.media.Schema;

/** 登录页面可读取的验证码配置，仅公开启用状态和有效期。 */
@Schema(description = "登录验证码配置")
public record CaptchaConfig(
        @Schema(description = "是否启用登录验证码") boolean enabled,
        @Schema(description = "验证码有效期，毫秒") long ttl) {}
