package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 当前项目的逻辑版本容量；替换版本文件不增加该用量。 */
@Schema(description = "项目版本容量响应")
public record VersionUsageResponse(
        @Schema(description = "原型项目标识") String projectId,
        @Schema(description = "项目内逻辑版本容量") CapacityUsage versions) {}
