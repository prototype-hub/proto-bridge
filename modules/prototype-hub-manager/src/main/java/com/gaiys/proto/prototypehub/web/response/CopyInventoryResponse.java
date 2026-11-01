package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 清单只暴露固定页面身份与纯文本，不包含存储路径、HTML或访问凭据。 */
@Schema(description = "固定页面文案清单上下文")
public record CopyInventoryResponse(
        String projectId,
        String versionId,
        String revisionId,
        String pageId,
        String pagePath,
        String manifestSha256,
        String fileSha256,
        String snapshotId,
        Integer extractorVersion,
        String extractionState,
        String limitReason,
        boolean staticComplete,
        boolean dynamicTextGap,
        int staticCount,
        int manualCount,
        Long configVersion,
        Long extractedAt) {
    @Schema(description = "静态原文出现位置；序号只用于来源说明，不是可执行选择器")
    public record Occurrence(int ordinal, String sourceKind) {}

    @Schema(description = "静态原文或人工补充，来源不可变")
    public record Entry(
            String id,
            String kind,
            String text,
            String businessTerm,
            String note,
            String requirementUrl,
            int occurrenceCount,
            List<Occurrence> occurrences,
            long configVersion,
            long createdAt,
            long updatedAt) {}

    @Schema(description = "数据库真实计数与分页结果")
    public record Page(
            CopyInventoryResponse snapshot,
            List<Entry> data,
            int total,
            int pageIndex,
            int pageSize) {}

    @Schema(description = "文案维护结果与整页版本")
    public record Saved(long inventoryVersion, Entry entry) {}

    @Schema(description = "人工补充删除结果")
    public record Deleted(long inventoryVersion, boolean deleted) {}

    @Schema(description = "固定页完整文案导出")
    public record Report(int formatVersion, CopyInventoryResponse snapshot, List<Entry> entries) {}
}
