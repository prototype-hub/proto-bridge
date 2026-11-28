package com.gaiys.proto.prototypehub.entity;

import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.Question;
import com.gaiys.proto.prototypehub.web.info.UsabilityStudyContent.Task;
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

/** 固定修订的人工研究定义；首次开放后冻结题目，状态与CAS独立于内容版本。 */
@Getter
@Setter
@Table(
        name = "p_usability_study",
        indexes = {
            @Index(name = "idx_usability_study_revision", columnList = "revision_id,updated_at,id"),
            @Index(name = "idx_usability_study_version", columnList = "version_id"),
            @Index(name = "idx_usability_study_project", columnList = "project_id")
        })
public class PrototypeUsabilityStudyEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    private String revisionId;

    @Column(name = "title", length = 120, nullable = false)
    private String title;

    @Column(name = "introduction", length = 4000, nullable = false)
    private String introduction;

    @Column(name = "consent_text", length = 4000, nullable = false)
    private String consentText;

    @Column(name = "tasks", nullable = false)
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<Task> tasks;

    @Column(name = "questions", nullable = false)
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<Question> questions;

    @Column(name = "state", length = 16, nullable = false)
    private String state;

    @Column(name = "content_version")
    private Long contentVersion;

    @Column(name = "config_version", nullable = false)
    private Long configVersion;

    @Column(name = "created_by", length = 64, nullable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 64, nullable = false)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
