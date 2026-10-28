package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 成员工作项的固定修订页面范围，不接受公开分享凭据。 */
@Getter
@Setter
@Schema(description = "工作项固定修订页面范围")
public class WorkItemPageRequest {
    @NotBlank
    @Size(max = 128)
    @Schema(description = "固定修订标识")
    private String revisionId;

    @NotBlank
    @Pattern(regexp = "[a-f0-9]{64}")
    @Schema(description = "清单页面稳定标识")
    private String pageId;
}
