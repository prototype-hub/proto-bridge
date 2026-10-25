package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 邀请管理视图，不返回完整邮箱、原始凭据或摘要。 */
@Schema(description = "脱敏邀请状态")
public record ReviewInvitationResponse(
        @Schema(description = "管理记录标识") String id,
        @Schema(description = "脱敏收件邮箱") String maskedEmail,
        @Schema(description = "pending/consumed/revoked/expired/invalidated") String status,
        @Schema(description = "邀请状态版本") long configVersion,
        @Schema(description = "创建时间（Unix毫秒）") long createdAt,
        @Schema(description = "到期时间（Unix毫秒）") long expiresAt,
        @Schema(description = "成功消费时间，未消费为空") Long consumedAt) {}
