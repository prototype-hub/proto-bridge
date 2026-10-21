package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 待认领项目的目标所有者，不接受客户端提交其他项目字段。 */
@Schema(description = "管理员为待认领项目指定有效所有者")
public record ProjectClaimRequest(
        @NotBlank @Size(max = 64) @Schema(description = "新的有效所有者用户标识") String userId) {}
