package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 创建邀请不发送邮件；分享版本用于拒绝陈旧管理页面。 */
@Schema(description = "创建固定分享邮箱邀请")
public record ReviewInvitationCreateRequest(
        @NotBlank @Email @Size(max = 254) @Schema(description = "待邀请邮箱") String email,
        @NotNull @PositiveOrZero @Schema(description = "当前分享配置版本") Long expectedShareVersion) {
    public ReviewInvitationCreateRequest {
        email = email == null ? null : email.strip();
    }
}
