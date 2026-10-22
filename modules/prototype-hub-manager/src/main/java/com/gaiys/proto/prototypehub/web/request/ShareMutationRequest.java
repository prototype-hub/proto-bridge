package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 永久撤销或链接轮换的并发前置条件。 */
@Schema(description = "原型分享状态变更请求")
public record ShareMutationRequest(
        @NotNull @PositiveOrZero @Schema(description = "客户端读取到的配置版本") Long expectedVersion) {}
