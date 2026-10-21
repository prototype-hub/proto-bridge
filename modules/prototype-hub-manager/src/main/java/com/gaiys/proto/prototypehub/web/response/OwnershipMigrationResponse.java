package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 显式迁移的扫描和分类计数，试运行与实际执行使用相同口径。 */
@Schema(description = "项目历史所有权回填的实际或试运行计数")
public record OwnershipMigrationResponse(
        @Schema(description = "扫描的项目数量") long scanned,
        @Schema(description = "本次可赋予或已赋予创建者所有权的数量") long assigned,
        @Schema(description = "找不到有效创建者而待认领的数量") long pending,
        @Schema(description = "已有所有者而保持不变的数量") long unchanged) {}
