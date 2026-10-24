package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** 固定修订对比的查询输入；Web 入口校验，两份清单分别分页，不解析当前修订。 */
@Getter
@Setter
@Schema(description = "同一逻辑版本的两个固定修订对比条件")
public class RevisionComparisonRequest {
    @NotBlank
    @Schema(description = "左侧固定修订标识")
    private String leftRevisionId;

    @NotBlank
    @Schema(description = "右侧固定修订标识，可与左侧相同")
    private String rightRevisionId;

    @Min(0)
    @Schema(description = "页面差异从零开始的页码", defaultValue = "0")
    private int pagesPageIndex;

    @Min(1)
    @Max(100)
    @Schema(description = "页面差异每页条数，一到一百", defaultValue = "50")
    private int pagesPageSize = 50;

    @Min(0)
    @Schema(description = "文件差异从零开始的页码", defaultValue = "0")
    private int filesPageIndex;

    @Min(1)
    @Max(100)
    @Schema(description = "文件差异每页条数，一到一百", defaultValue = "50")
    private int filesPageSize = 50;

    @Schema(description = "是否在分页结果中包含未变化项，汇总始终包含全部项", defaultValue = "false")
    private boolean includeUnchanged;
}
