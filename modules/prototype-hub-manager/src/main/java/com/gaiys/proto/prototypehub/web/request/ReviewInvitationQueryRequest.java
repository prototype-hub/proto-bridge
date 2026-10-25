package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** 只接受有界页码，不提供邮箱回读或任意查询条件。 */
@Getter
@Setter
@Schema(description = "固定分享邀请分页请求")
public class ReviewInvitationQueryRequest {
    @NotNull
    @Min(0)
    @Max(10000)
    @Schema(description = "从零开始的页码", defaultValue = "0")
    private Integer pageIndex = 0;

    @NotNull
    @Min(1)
    @Max(100)
    @Schema(description = "每页条数，一到一百", defaultValue = "20")
    private Integer pageSize = 20;
}
