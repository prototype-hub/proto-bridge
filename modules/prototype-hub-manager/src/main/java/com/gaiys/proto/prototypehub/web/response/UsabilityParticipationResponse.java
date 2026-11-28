package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeUsabilityResponseEntity;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.Answer;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.TaskAnswer;
import java.util.List;

/** 本人答卷不回传原始身份或时间；写回执重放返回当前状态而不是首次成功快照。 */
public record UsabilityParticipationResponse(
        String id,
        long contentVersion,
        String status,
        long configVersion,
        List<TaskAnswer> taskAnswers,
        List<Answer> answers,
        String feedback) {
    public static UsabilityParticipationResponse of(PrototypeUsabilityResponseEntity row) {
        return new UsabilityParticipationResponse(
                row.getId(),
                row.getContentVersion(),
                row.getStatus(),
                row.getConfigVersion(),
                row.getTaskAnswers(),
                row.getAnswers(),
                row.getFeedback());
    }

    public record MutationResult(String status, UsabilityParticipationResponse participation) {
        public static MutationResult current(PrototypeUsabilityResponseEntity row) {
            return new MutationResult("current", of(row));
        }

        public static MutationResult missing() {
            return new MutationResult("missing", null);
        }
    }

    /** 仅当前成员可读的已提交答卷，排除guest/share/grade及逐条时间。 */
    public record Member(
            String id,
            long contentVersion,
            String status,
            List<TaskAnswer> taskAnswers,
            List<Answer> answers,
            String feedback) {
        public static Member of(PrototypeUsabilityResponseEntity row) {
            return new Member(
                    row.getId(),
                    row.getContentVersion(),
                    row.getStatus(),
                    row.getTaskAnswers(),
                    row.getAnswers(),
                    row.getFeedback());
        }
    }
}
