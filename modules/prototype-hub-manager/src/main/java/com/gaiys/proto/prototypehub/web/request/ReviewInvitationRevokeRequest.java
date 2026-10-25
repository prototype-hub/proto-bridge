package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 撤销未使用邀请，不撤销已有访客身份或会话。 */
@Schema(description = "撤销邮箱邀请")
public record ReviewInvitationRevokeRequest(
        @NotNull @PositiveOrZero @Schema(description = "当前邀请配置版本") Long expectedVersion) {}
