package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 显式启停分享，同时使此前授予的公开凭据失效。 */
@Schema(description = "原型分享启停请求")
public record ShareEnabledRequest(
        @NotNull @PositiveOrZero @Schema(description = "客户端读取到的配置版本") Long expectedVersion,
        @NotNull @Schema(description = "是否启用分享") Boolean enabled) {}
