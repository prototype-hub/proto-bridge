package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 显式申请未验证身份；不接收邮箱，也不触发邮件或恢复其他访客身份。 */
@Schema(description = "创建短期未验证访客请求")
public record ReviewUnverifiedRequest(
        @NotBlank @Size(max = 128) @Schema(description = "固定修订标识") String revisionId,
        @NotBlank @Size(max = 256) @Schema(description = "当前分享授权凭据") String accessKey,
        @NotBlank
                @Size(max = 100)
                @Pattern(regexp = "(?s)(?=.*\\P{javaWhitespace})[^\\p{Cc}]*")
                @Schema(description = "未经核验的自述名称")
                String displayName,
        @Size(max = 200) @Pattern(regexp = "[^\\p{Cc}]*") @Schema(description = "未经核验的自述公司，可为空")
                String company) {}
