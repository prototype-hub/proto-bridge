package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 从实际资源行数和有效额度计算容量，不维护可能随失败或删除漂移的独立计数器。 */
@Schema(description = "原型资源容量")
public record CapacityUsage(
        @Schema(description = "当前已使用数量") long used,
        @Schema(description = "允许创建的数量上限，-1 表示不限") int limit,
        @Schema(description = "剩余可创建数量，有限时最低为零，-1 表示不限") long remaining,
        @Schema(description = "已有数据是否超过上限，不限时为 false") boolean overLimit,
        @Schema(description = "容量是否允许继续创建，仍须校验操作权限和资源状态") boolean canCreate) {
    public static CapacityUsage from(long used, int limit) {
        if (limit == -1) return new CapacityUsage(used, limit, -1, false, true);
        return new CapacityUsage(
                used, limit, Math.max(0, limit - used), used > limit, used < limit);
    }
}
