package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 固定修订清单内的字面路径搜索及分页；不改变数据库资源范围。 */
@Getter
@Setter
@Schema(description = "原始资产清单查询")
public class AssetQueryRequest {
    @Min(0)
    @Max(10000)
    private int pageIndex;

    @Min(1)
    @Max(100)
    private int pageSize = 50;

    @Size(max = 256)
    private String search;
}
