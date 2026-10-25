package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 固定修订页面定位；公开入口额外校验分享凭据，管理端不接收作者身份。 */
@Getter
@Setter
@Schema(description = "固定修订页面评审范围")
public class ReviewPageRequest {
    public interface Public {}

    @NotBlank
    @Size(max = 128)
    @Schema(description = "固定修订标识")
    private String revisionId;

    @NotBlank
    @Pattern(regexp = "[a-f0-9]{64}")
    @Schema(description = "清单稳定页面标识")
    private String pageId;

    @NotBlank(groups = Public.class)
    @Size(max = 256)
    @Schema(description = "公开分享现有访问凭据，仅公开入口需要")
    private String accessKey;
}
