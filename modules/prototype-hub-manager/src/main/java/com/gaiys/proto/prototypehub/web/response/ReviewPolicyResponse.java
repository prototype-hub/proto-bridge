package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 分享管理侧的访客身份策略，低保障访问须另行显式允许。 */
@Schema(description = "分享访客身份策略")
public record ReviewPolicyResponse(
        @Schema(description = "是否启用访客身份") boolean enabled,
        @Schema(description = "是否明确允许未验证访客") boolean allowUnverified,
        @Schema(description = "当前是否允许页面文本评审") boolean allowComment,
        @Schema(description = "是否配置验证邮件传输") boolean mailAvailable,
        @Schema(description = "当前分享配置并发版本") long configVersion) {}
