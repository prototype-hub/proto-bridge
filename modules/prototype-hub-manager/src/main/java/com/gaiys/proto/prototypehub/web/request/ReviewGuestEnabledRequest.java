package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 显式切换访客状态；真实切换撤销旧验证码和会话，恢复后仍需重新验证。 */
@Schema(description = "项目邮箱访客启停请求")
public record ReviewGuestEnabledRequest(
        @NotNull @Schema(description = "是否启用访客身份") Boolean enabled,
        @NotNull @PositiveOrZero @Schema(description = "客户端读取到的访客配置版本") Long expectedVersion) {}
