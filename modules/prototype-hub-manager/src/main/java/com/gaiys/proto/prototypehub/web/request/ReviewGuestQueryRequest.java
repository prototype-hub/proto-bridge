package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** 项目邮箱访客的有界分页输入，不接受邮箱检索或任意数据库条件。 */
@Getter
@Setter
@Schema(description = "项目邮箱访客分页请求")
public class ReviewGuestQueryRequest {
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
}
