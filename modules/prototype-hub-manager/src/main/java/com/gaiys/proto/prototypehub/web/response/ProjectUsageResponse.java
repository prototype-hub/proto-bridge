package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 当前数据库共享工作区的项目容量。 */
@Schema(description = "项目容量响应")
public record ProjectUsageResponse(
        @Schema(description = "持久化部署标识") String installationId,
        @Schema(description = "部署内项目容量") CapacityUsage projects) {}
