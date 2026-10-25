package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** 页面线程有界分页；仅允许精确状态。 */
@Getter
@Setter
@Schema(description = "页面线程有界分页；仅允许精确状态。")
public class ReviewThreadQueryRequest extends ReviewPageRequest {
    @Min(0)
    @Max(10000)
    @Schema(description = "零起始页号")
    private int pageIndex = 0;

    @Min(1)
    @Max(100)
    @Schema(description = "每页数量")
    private int pageSize = 20;

    @Pattern(regexp = "open|resolved")
    @Schema(description = "可选精确线程状态")
    private String state;
}
