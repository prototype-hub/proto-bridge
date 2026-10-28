package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeWorkItemEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/** 已授权的固定页面工作项引用，不包含账号或分享凭据。 */
@Schema(description = "固定页面工作项关联")
public record WorkItemResponse(
        @Schema(description = "关联标识") String id,
        @Schema(description = "项目标识") String projectId,
        @Schema(description = "逻辑版本标识") String versionId,
        @Schema(description = "固定修订标识") String revisionId,
        @Schema(description = "固定页面标识") String pageId,
        @Schema(description = "外部HTTP或HTTPS地址") String url,
        @Schema(description = "纯文本备注") String note,
        @Schema(description = "并发版本") long configVersion,
        @Schema(description = "创建Unix毫秒") long createdAt,
        @Schema(description = "更新Unix毫秒") long updatedAt) {
    public static WorkItemResponse of(PrototypeWorkItemEntity row) {
        return new WorkItemResponse(
                row.getId(),
                row.getProjectId(),
                row.getVersionId(),
                row.getRevisionId(),
                row.getPageId(),
                row.getUrl(),
                row.getNote(),
                row.getConfigVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
