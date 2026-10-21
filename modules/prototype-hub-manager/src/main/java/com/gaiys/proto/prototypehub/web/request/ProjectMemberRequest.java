package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 项目成员角色请求，所有权通过独立认领边界维护。 */
@Schema(description = "新增或修改项目成员角色，不允许通过成员接口变更所有者")
public record ProjectMemberRequest(
        @NotNull @Pattern(regexp = "editor|viewer") @Schema(description = "成员角色，editor 或 viewer")
                String role) {}
