package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 项目和版本列表的有界查询输入，由 Web 入口统一校验。
 *
 * <p>只接收公开的名称筛选和排序选项；数据库条件、授权范围及真实总数均由服务端生成，不接收任意查询表达式。
 */
@Getter
@Setter
@Schema(description = "原型项目和版本的分页查询条件")
public class PrototypeListQueryRequest {
    @NotNull
    @Min(0)
    @Max(10000)
    @Schema(description = "从零开始的页码，最大一万", defaultValue = "0")
    private Integer pageIndex = 0;

    @NotNull
    @Min(1)
    @Max(100)
    @Schema(description = "每页条数，范围一到一百", defaultValue = "20")
    private Integer pageSize = 20;

    @Size(max = 128)
    @Schema(description = "名称包含的字面关键词，区分大小写，去除首尾空白后为空则不筛选")
    private String keyword;

    @NotNull
    @Pattern(regexp = "createTime|name")
    @Schema(description = "排序字段，name 对应项目名称或版本名称", defaultValue = "createTime")
    private String sortBy = "createTime";

    @NotNull
    @Pattern(regexp = "asc|desc")
    @Schema(description = "排序方向，asc 为升序，desc 为降序", defaultValue = "desc")
    private String sortOrder = "desc";
}
