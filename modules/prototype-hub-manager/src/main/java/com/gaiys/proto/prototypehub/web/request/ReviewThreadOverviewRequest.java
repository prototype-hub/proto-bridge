package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 固定修订跨页面总览；不改变逐页请求必须提供稳定页面标识的契约。 */
@Getter
@Setter
@Schema(description = "固定修订全部页面反馈有界分页")
public class ReviewThreadOverviewRequest {
    @NotBlank
    @Size(max = 128)
    @Schema(description = "固定修订标识")
    private String revisionId;

    @NotBlank(groups = ReviewPageRequest.Public.class)
    @Size(max = 256)
    @Schema(description = "现有公开分享访问凭据，仅公开入口需要")
    private String accessKey;

    @Min(0)
    @Max(10000)
    @Schema(description = "零起始页号")
    private int pageIndex = 0;

    @Min(1)
    @Max(100)
    @Schema(description = "每页数量，默认二十条")
    private int pageSize = 20;

    @Pattern(regexp = "open|resolved")
    @Schema(description = "可选精确线程状态，open或resolved")
    private String state;
}
