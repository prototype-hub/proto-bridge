package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 当前账号实时权限内的资源存量与有效外层分享访问量，不是访客人数。 */
@Schema(description = "授权范围内的项目与分享访问统计")
public record PrototypeStatisticsResponse(
        @Schema(description = "统计自然日时区") String timeZone,
        @Schema(description = "窗口首日，含当天，yyyy-MM-dd") String fromDate,
        @Schema(description = "窗口末日，含当天，yyyy-MM-dd") String toDate,
        @Schema(description = "首次启用采集Unix毫秒；此前历史未采集") long collectedSince,
        @Schema(description = "事件保留自然日数") int retentionDays,
        @Schema(description = "可读活动项目数，包含归档项目") long projectCount,
        @Schema(description = "可读活动逻辑版本数；无版本查询动作为null") Long versionCount,
        @Schema(description = "可管理且有效分享数；无分享查询动作为null") Long shareCount,
        @Schema(description = "窗口内已记录外层打开次数；无分享查询动作为null，不是人数") Long visits,
        @Schema(description = "最多30项按日趋势；无分享查询动作为null") List<Trend> trend,
        @Schema(description = "最多10项非零项目排行；无分享查询动作为null") List<Ranking> ranking) {
    /** 起算日期之前为null，已采集日期没有事件为0。 */
    @Schema(description = "单日访问量")
    public record Trend(
            @Schema(description = "自然日yyyy-MM-dd") String date,
            @Schema(description = "已记录访问数，起算日前为null") Long visits) {}

    /** 仅返回当前仍有分享管理权限的项目，最多10项。 */
    @Schema(description = "当前授权内访问排行")
    public record Ranking(
            @Schema(description = "项目标识") String projectId,
            @Schema(description = "当前项目名称") String projectName,
            @Schema(description = "窗口内已记录访问数") long visits) {}
}
