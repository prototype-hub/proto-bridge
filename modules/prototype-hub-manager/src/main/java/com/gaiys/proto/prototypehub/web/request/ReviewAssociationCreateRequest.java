package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 人工授权CAS输入；重试保留相同请求键与全部语义，不接收来源访客ID或范围。 */
@Getter
@Setter
public class ReviewAssociationCreateRequest extends ReviewAssociationContextRequest {
    @NotBlank
    @Size(max = 64)
    @Schema(description = "目标邮箱访客标识")
    private String targetGuestId;

    @NotNull
    @PositiveOrZero
    @Schema(description = "读取到的分享配置版本")
    private Long expectedShareVersion;

    @NotNull
    @PositiveOrZero
    @Schema(description = "读取到的目标访客配置版本")
    private Long expectedTargetVersion;

    @NotBlank
    @Size(max = 1000)
    @Schema(description = "操作人记录的线下核实依据或记录编号")
    private String evidenceReference;

    @NotBlank
    @Pattern(regexp = "[!-~]{1,128}")
    @Schema(description = "幂等请求键，未知结果重试保持不变")
    private String clientRequestId;

    public void setTargetGuestId(Object value) {
        if (!(value instanceof String))
            throw new IllegalArgumentException("targetGuestId must be a string");
        targetGuestId = (String) value;
    }
}
