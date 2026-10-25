package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 仅选中线程范围内的有效项目负责人候选，不暴露全局账号搜索。 */
@Getter
@Setter
@Schema(description = "线程分派候选有界分页")
public class ReviewAssignmentCandidateRequest extends ReviewPageRequest {
    @Min(0)
    @Max(10000)
    @Schema(description = "零起始页号")
    private int pageIndex = 0;

    @Min(1)
    @Max(50)
    @Schema(description = "每页数量，默认二十")
    private int pageSize = 20;

    @Size(max = 100)
    @Schema(description = "按姓名字面包含筛选，不解释通配符")
    private String keyword;
}
