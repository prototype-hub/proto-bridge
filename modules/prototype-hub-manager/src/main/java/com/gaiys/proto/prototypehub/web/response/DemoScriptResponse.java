package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeDemoScriptEntity;
import com.gaiys.proto.prototypehub.web.info.DemoScriptStep;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 当前授权成员读取的完整脚本；公开投影不包含内部范围或并发元数据。 */
@Schema(description = "固定修订人工演示脚本")
public record DemoScriptResponse(
        String id,
        String projectId,
        String versionId,
        String revisionId,
        String title,
        String objective,
        String prerequisites,
        String resetNote,
        List<DemoScriptStep> steps,
        boolean shareEnabled,
        long configVersion,
        long createdAt,
        long updatedAt) {
    public static DemoScriptResponse of(PrototypeDemoScriptEntity row) {
        return new DemoScriptResponse(
                row.getId(),
                row.getProjectId(),
                row.getVersionId(),
                row.getRevisionId(),
                row.getTitle(),
                row.getObjective(),
                row.getPrerequisites(),
                row.getResetNote(),
                row.getSteps(),
                row.getShareEnabled(),
                row.getConfigVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    /** 列表不加载有界但较大的讲解及步骤正文。 */
    public record Summary(
            String id,
            String projectId,
            String versionId,
            String revisionId,
            String title,
            boolean shareEnabled,
            long configVersion,
            long createdAt,
            long updatedAt) {
        public static Summary of(PrototypeDemoScriptEntity row) {
            return new Summary(
                    row.getId(),
                    row.getProjectId(),
                    row.getVersionId(),
                    row.getRevisionId(),
                    row.getTitle(),
                    row.getShareEnabled(),
                    row.getConfigVersion(),
                    row.getCreatedAt(),
                    row.getUpdatedAt());
        }
    }

    /** 分享列表只暴露当前授权修订中已开放脚本的定位和标题。 */
    public record PublicSummary(String id, String revisionId, String title) {
        public static PublicSummary of(PrototypeDemoScriptEntity row) {
            return new PublicSummary(row.getId(), row.getRevisionId(), row.getTitle());
        }
    }

    /** EDIT显式开放的是完整正文；没有附加内部备注或历史。 */
    public record PublicContext(
            String id,
            String revisionId,
            String title,
            String objective,
            String prerequisites,
            String resetNote,
            List<DemoScriptStep> steps) {
        public static PublicContext of(PrototypeDemoScriptEntity row) {
            return new PublicContext(
                    row.getId(),
                    row.getRevisionId(),
                    row.getTitle(),
                    row.getObjective(),
                    row.getPrerequisites(),
                    row.getResetNote(),
                    row.getSteps());
        }
    }
}
