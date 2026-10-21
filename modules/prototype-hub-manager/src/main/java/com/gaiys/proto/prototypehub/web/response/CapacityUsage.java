package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 从实际资源行数计算容量，不维护可能随失败或删除漂移的独立计数器。 */
@Schema(description = "原型资源容量")
public record CapacityUsage(
        @Schema(description = "当前已使用数量") long used,
        @Schema(description = "允许创建的数量上限") int limit,
        @Schema(description = "剩余可创建数量，最低为零") long remaining,
        @Schema(description = "已有数据是否超过上限") boolean overLimit,
        @Schema(description = "当前是否允许继续创建") boolean canCreate) {
    public static final int LIMIT = 5;

    public static CapacityUsage from(long used) {
        return new CapacityUsage(
                used, LIMIT, Math.max(0, LIMIT - used), used > LIMIT, used < LIMIT);
    }
}
