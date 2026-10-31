package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** 来源固定范围内的授权审计分页，不接受任意数据库条件。 */
@Getter
@Setter
public class ReviewAssociationQueryRequest extends ReviewAssociationContextRequest {
    @NotNull
    @Min(0)
    @Max(10000)
    @Schema(description = "从零开始的页码，最大一万")
    private Integer pageIndex = 0;

    @NotNull
    @Min(1)
    @Max(100)
    @Schema(description = "每页一至一百条")
    private Integer pageSize = 20;
}
