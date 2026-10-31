package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 独立授权版本的撤销；失效授权仍可撤销，已失去的会话不会恢复。 */
public record ReviewAssociationRevokeRequest(
        @NotNull @PositiveOrZero @Schema(description = "读取到的独立授权状态版本") Long expectedVersion,
        @NotBlank @Size(max = 1000) @Schema(description = "人工撤销原因") String reason,
        @NotBlank @Pattern(regexp = "[!-~]{1,128}") @Schema(description = "幂等请求键，未知结果重试保持不变")
                String clientRequestId) {}
