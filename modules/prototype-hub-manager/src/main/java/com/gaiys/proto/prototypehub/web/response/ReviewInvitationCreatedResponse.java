package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 原始凭据仅在创建响应返回一次；由用户明确复制，不发送邀请邮件。 */
@Schema(description = "新邀请及一次性展示凭据")
public record ReviewInvitationCreatedResponse(
        @Schema(description = "邀请脱敏信息") ReviewInvitationResponse invitation,
        @Schema(description = "仅本次返回的URL安全邀请凭据") String invitationToken) {}
