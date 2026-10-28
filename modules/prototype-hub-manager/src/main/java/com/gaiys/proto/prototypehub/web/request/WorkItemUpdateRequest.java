package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

/** 使用读取到的并发版本明确替换工作项内容。 */
@Getter
@Setter
@Schema(description = "更新工作项关联")
public class WorkItemUpdateRequest extends WorkItemCreateRequest {
    @NotNull
    @PositiveOrZero
    @Schema(description = "读取到的并发版本")
    private Long expectedVersion;
}
