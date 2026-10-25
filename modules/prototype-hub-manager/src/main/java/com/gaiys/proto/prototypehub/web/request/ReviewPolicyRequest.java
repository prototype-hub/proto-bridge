package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 分享身份策略的CAS变更；旧客户端未传低保障开关时保留该项现值。 */
@Schema(description = "分享访客身份策略变更")
public record ReviewPolicyRequest(
        @NotNull @PositiveOrZero @Schema(description = "读取到的分享配置版本") Long expectedVersion,
        @NotNull @Schema(description = "是否开启访客身份") Boolean enabled,
        @Schema(description = "是否明确允许未验证访客；未传或空值保留现值") Boolean allowUnverified,
        @Schema(description = "是否允许页面文本评审；未传或空值保留现值") Boolean allowComment) {}
