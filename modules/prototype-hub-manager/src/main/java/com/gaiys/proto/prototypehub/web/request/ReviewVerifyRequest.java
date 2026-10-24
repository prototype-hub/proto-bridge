package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 固定分享快照的邮箱身份请求；授权凭据不用于平台登录。 */
@Schema(description = "公开分享邮箱身份请求")
public record ReviewVerifyRequest(
        @NotBlank @Size(max = 128) @Schema(description = "固定修订标识") String revisionId,
        @NotBlank @Size(max = 256) @Schema(description = "现有分享授权凭据") String accessKey,
        @NotBlank @Size(max = 64) @Schema(description = "挑战标识") String challengeId,
        @Pattern(regexp = "[0-9]{6}") @NotBlank @Schema(description = "六位ASCII验证码") String code) {}
