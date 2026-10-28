package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

/** 仅删除当前页面的关联，不操作外部工作项或原型文件。 */
@Getter
@Setter
@Schema(description = "删除工作项关联")
public class WorkItemDeleteRequest extends WorkItemPageRequest {
    @NotNull
    @PositiveOrZero
    @Schema(description = "读取到的并发版本")
    private Long expectedVersion;

    @NotBlank
    @Pattern(regexp = "[!-~]{1,128}")
    @Schema(description = "强制幂等请求键，重试保留原输入")
    private String clientRequestId;
}
