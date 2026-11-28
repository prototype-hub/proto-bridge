package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeUsabilityStudyEntity;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.Question;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.Task;
import java.util.List;

/** 成员研究定义；公开投影不携带内部归属、维护身份、时间及CAS元数据。 */
public record UsabilityStudyResponse(
        String id,
        String projectId,
        String versionId,
        String revisionId,
        String title,
        String introduction,
        String consentText,
        List<Task> tasks,
        List<Question> questions,
        String state,
        Long contentVersion,
        long configVersion,
        String createdBy,
        String updatedBy,
        long createdAt,
        long updatedAt) {
    public static UsabilityStudyResponse of(PrototypeUsabilityStudyEntity row) {
        return new UsabilityStudyResponse(
                row.getId(),
                row.getProjectId(),
                row.getVersionId(),
                row.getRevisionId(),
                row.getTitle(),
                row.getIntroduction(),
                row.getConsentText(),
                row.getTasks(),
                row.getQuestions(),
                row.getState(),
                row.getContentVersion(),
                row.getConfigVersion(),
                row.getCreatedBy(),
                row.getUpdatedBy(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    public record Summary(
            String id,
            String projectId,
            String versionId,
            String revisionId,
            String title,
            String state,
            Long contentVersion,
            long configVersion,
            long createdAt,
            long updatedAt) {
        public static Summary of(PrototypeUsabilityStudyEntity row) {
            return new Summary(
                    row.getId(),
                    row.getProjectId(),
                    row.getVersionId(),
                    row.getRevisionId(),
                    row.getTitle(),
                    row.getState(),
                    row.getContentVersion(),
                    row.getConfigVersion(),
                    row.getCreatedAt(),
                    row.getUpdatedAt());
        }
    }

    public record PublicSummary(String id, String title, String state) {
        public static PublicSummary of(PrototypeUsabilityStudyEntity row) {
            return new PublicSummary(row.getId(), row.getTitle(), row.getState());
        }
    }

    public record PublicStudy(
            String id,
            String title,
            String introduction,
            String consentText,
            List<Task> tasks,
            List<Question> questions,
            String state,
            long contentVersion) {
        public static PublicStudy of(PrototypeUsabilityStudyEntity row) {
            return new PublicStudy(
                    row.getId(),
                    row.getTitle(),
                    row.getIntroduction(),
                    row.getConsentText(),
                    row.getTasks(),
                    row.getQuestions(),
                    row.getState(),
                    row.getContentVersion());
        }
    }

    public record PublicContext(PublicStudy study, UsabilityParticipationResponse participation) {}
}
