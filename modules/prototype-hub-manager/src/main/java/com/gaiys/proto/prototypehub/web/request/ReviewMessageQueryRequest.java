package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/** 线程消息有界分页；公开端服务层固定过滤内部备注。 */
@Getter
@Setter
@Schema(description = "线程消息有界分页；公开端服务层固定过滤内部备注。")
public class ReviewMessageQueryRequest extends ReviewPageRequest {
    @Min(0)
    @Max(10000)
    @Schema(description = "零起始页号")
    private int pageIndex = 0;

    @Min(1)
    @Max(100)
    @Schema(description = "每页数量")
    private int pageSize = 20;
}
