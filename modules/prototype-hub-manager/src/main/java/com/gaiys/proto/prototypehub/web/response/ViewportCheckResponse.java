package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeViewportCheckEntity;
import com.gaiys.proto.prototypehub.enums.ViewportCheckCategory;
import com.gaiys.proto.prototypehub.enums.ViewportCheckResult;
import io.swagger.v3.oas.annotations.media.Schema;

/** 已授权成员填写的人工记录；枚举输出value/text，结论不代表自动验收。 */
@Schema(description = "固定页面人工视口自测记录")
public record ViewportCheckResponse(
        @Schema(description = "记录标识") String id,
        @Schema(description = "项目标识") String projectId,
        @Schema(description = "逻辑版本标识") String versionId,
        @Schema(description = "固定修订标识") String revisionId,
        @Schema(description = "固定页面标识") String pageId,
        @Schema(description = "CSS视口宽度") int viewportWidth,
        @Schema(description = "CSS视口高度") int viewportHeight,
        @Schema(description = "舞台显示缩放百分比，不是DPR") int viewportScale,
        @Schema(description = "场景类别，包含value和当前语言text") ViewportCheckCategory category,
        @Schema(description = "人工场景标题") String title,
        @Schema(description = "人工操作步骤") String steps,
        @Schema(description = "人工预期") String expected,
        @Schema(description = "人工观察") String observed,
        @Schema(description = "人工结论，包含value和当前语言text") ViewportCheckResult result,
        @Schema(description = "人工纯文本证据，不是附件") String evidence,
        @Schema(description = "并发版本") long configVersion,
        @Schema(description = "创建Unix毫秒") long createdAt,
        @Schema(description = "最近维护Unix毫秒") long updatedAt) {
    public static ViewportCheckResponse of(PrototypeViewportCheckEntity row) {
        return new ViewportCheckResponse(
                row.getId(),
                row.getProjectId(),
                row.getVersionId(),
                row.getRevisionId(),
                row.getPageId(),
                row.getViewportWidth(),
                row.getViewportHeight(),
                row.getViewportScale(),
                ViewportCheckCategory.valueOf(row.getCategory()),
                row.getTitle(),
                row.getSteps(),
                row.getExpected(),
                row.getObserved(),
                ViewportCheckResult.valueOf(row.getResult()),
                row.getEvidence(),
                row.getConfigVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
