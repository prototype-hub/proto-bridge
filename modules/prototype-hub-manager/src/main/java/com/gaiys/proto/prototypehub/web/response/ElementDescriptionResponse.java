package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeElementDescriptionEntity;
import com.gaiys.proto.prototypehub.web.info.ReviewAnchor;
import io.swagger.v3.oas.annotations.media.Schema;

/** 仅向当前有READ权限的内部成员返回说明正文及静态几何快照。 */
@Schema(description = "固定页面区域人工说明")
public record ElementDescriptionResponse(
        String id,
        String projectId,
        String versionId,
        String revisionId,
        String pageId,
        String title,
        String purpose,
        String inputRules,
        String permissionRules,
        String navigationRules,
        String notes,
        ReviewAnchor anchor,
        long configVersion,
        long createdAt,
        long updatedAt) {
    public static ElementDescriptionResponse of(PrototypeElementDescriptionEntity row) {
        return new ElementDescriptionResponse(
                row.getId(),
                row.getProjectId(),
                row.getVersionId(),
                row.getRevisionId(),
                row.getPageId(),
                row.getTitle(),
                row.getPurpose(),
                row.getInputRules(),
                row.getPermissionRules(),
                row.getNavigationRules(),
                row.getNotes(),
                row.getAnchor(),
                row.getConfigVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
