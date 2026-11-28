package com.gaiys.proto.prototypehub.entity;

import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.Answer;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.TaskAnswer;
import java.sql.JDBCType;
import java.util.List;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 分享访客的同意及本人答卷；不存邮箱/Cookie，撤回保留最小身份标记且清空内容。 */
@Getter
@Setter
@Table(
        name = "p_usability_response",
        indexes = {
            @Index(
                    name = "idx_usability_response_study",
                    columnList = "study_id,status,updated_at,id"),
            @Index(name = "idx_usability_response_version", columnList = "version_id"),
            @Index(name = "idx_usability_response_project", columnList = "project_id"),
            @Index(
                    name = "idx_usability_response_identity",
                    columnList = "share_id,guest_id,verification_level")
        })
public class PrototypeUsabilityResponseEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    private String revisionId;

    @Column(name = "study_id", length = 64, nullable = false)
    private String studyId;

    @Column(name = "share_id", length = 64, nullable = false)
    private String shareId;

    @Column(name = "guest_id", length = 64, nullable = false)
    private String guestId;

    @Column(name = "verification_level", length = 16, nullable = false)
    private String verificationLevel;

    @Column(name = "content_version", nullable = false)
    private Long contentVersion;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "consented_at", nullable = false)
    private Long consentedAt;

    @Column(name = "task_answers", nullable = false)
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<TaskAnswer> taskAnswers;

    @Column(name = "answers", nullable = false)
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<Answer> answers;

    @Column(name = "feedback", length = 2000, nullable = false)
    private String feedback;

    @Column(name = "config_version", nullable = false)
    private Long configVersion;

    @Column(name = "submitted_at")
    private Long submittedAt;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
