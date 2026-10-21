package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 历史项目所有权迁移的显式写入开关。 */
@Schema(description = "显式历史所有权迁移，默认使用 apply=false 试运行")
public record OwnershipMigrationRequest(
        @NotNull @Schema(description = "false 仅检查计数，true 执行幂等回填") Boolean apply) {}
