package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/** 固定页面关联工作项的有界数据库分页。 */
@Getter
@Setter
@Schema(description = "工作项分页查询")
public class WorkItemQueryRequest extends WorkItemPageRequest {
    @Min(0)
    @Max(10000)
    @Schema(description = "零起始页号")
    private int pageIndex = 0;

    @Min(1)
    @Max(100)
    @Schema(description = "每页数量")
    private int pageSize = 20;
}
