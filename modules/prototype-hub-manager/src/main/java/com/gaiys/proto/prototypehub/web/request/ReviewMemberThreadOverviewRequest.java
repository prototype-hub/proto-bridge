package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** 成员专用负责人筛选；当前用户由服务端解析，公开总览不接受该查询语义。 */
@Getter
@Setter
@Schema(description = "成员固定修订反馈总览，可按当前用户或未分派筛选")
public class ReviewMemberThreadOverviewRequest extends ReviewThreadOverviewRequest {
    @Pattern(regexp = "mine|unassigned")
    @Schema(description = "可选精确负责人范围：mine分派给当前用户，unassigned未分派，空值查询全部")
    private String assignment;
}
