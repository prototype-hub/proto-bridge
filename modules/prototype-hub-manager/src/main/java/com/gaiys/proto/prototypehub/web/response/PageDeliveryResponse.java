package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypePageDeliveryEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/** 已授权固定页面的人工交付结论；未设置不映射为草稿或上传成功，负责人也不产生权限。 */
@Schema(description = "固定页面人工交付状态")
public record PageDeliveryResponse(
        String projectId,
        String versionId,
        String revisionId,
        String pageId,
        String pageName,
        String pagePath,
        @Schema(description = "未设置时为null，否则DRAFT/READY/IN_PROGRESS/DONE") String state,
        Assignee assignee,
        String reason,
        long configVersion,
        Long updatedAt) {
    /** 已保存姓名快照与当前资格分离，账号/成员失效后不抹去历史。 */
    @Schema(description = "人工负责人快照及当前可选资格")
    public record Assignee(String userId, String name, boolean available) {}

    public static PageDeliveryResponse of(PrototypePageDeliveryEntity row, boolean available) {
        return new PageDeliveryResponse(
                row.getProjectId(),
                row.getVersionId(),
                row.getRevisionId(),
                row.getPageId(),
                row.getPageName(),
                row.getPagePath(),
                row.getState(),
                row.getAssigneeId() == null
                        ? null
                        : new Assignee(row.getAssigneeId(), row.getAssigneeName(), available),
                row.getReason(),
                row.getConfigVersion(),
                row.getUpdatedAt());
    }
}
