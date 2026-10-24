package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 分享管理侧的可选邮箱身份策略。 */
@Schema(description = "分享邮箱身份策略")
public record ReviewPolicyResponse(
        @Schema(description = "是否启用邮箱身份") boolean enabled,
        @Schema(description = "是否配置验证邮件传输") boolean mailAvailable,
        @Schema(description = "当前分享配置并发版本") long configVersion) {}
