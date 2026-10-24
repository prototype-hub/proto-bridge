package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 验证邮件接受后的挑战回执；不包含验证码、邮箱或摘要。 */
@Schema(description = "邮箱验证挑战回执")
public record ReviewChallengeResponse(
        @Schema(description = "一次性挑战标识") String challengeId,
        @Schema(description = "挑战到期Unix毫秒") long expiresAt,
        @Schema(description = "最早可重发Unix毫秒") long resendAfter) {}
