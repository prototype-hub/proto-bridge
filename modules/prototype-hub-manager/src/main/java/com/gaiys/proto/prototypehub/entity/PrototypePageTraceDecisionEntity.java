package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 固定修订人工追踪元数据；不改变原型、来源生命周期或正式批准。 */
@Getter
@Setter
@Table(
        name = "p_page_trace_decision",
        indexes = {
            @Index(
                    name = "idx_page_trace_dec_page",
                    columnList = "revision_id,page_id,created_at,id"),
            @Index(name = "idx_page_trace_dec_version", columnList = "version_id"),
            @Index(name = "idx_page_trace_dec_project", columnList = "project_id")
        })
@Schema(description = "固定修订页面人工追踪元数据")
public class PrototypePageTraceDecisionEntity extends GenericEntity<String> {
    @Column(name = "project_id", nullable = false, length = 64)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", nullable = false, length = 64)
    @Schema(description = "所属逻辑版本标识")
    private String versionId;

    @Column(name = "revision_id", nullable = false, length = 64)
    @Schema(description = "不可变修订标识，不随当前修订迁移")
    private String revisionId;

    @Column(name = "page_id", nullable = false, length = 128)
    @Schema(description = "固定修订真实内容页标识")
    private String pageId;

    @Column(name = "title", nullable = false, length = 120)
    @Schema(description = "人工决策标题，普通文本")
    private String title;

    @Column(name = "decision", nullable = false, length = 2000)
    @Schema(description = "人工决定，独立于正式批准")
    private String decision;

    @Column(name = "rationale", nullable = false, length = 2000)
    @Schema(description = "人工决定理由，普通文本")
    private String rationale;

    @Column(name = "author_id", nullable = false, length = 64)
    @Schema(description = "首次记录成员标识，由服务端拥有")
    private String authorId;

    @Column(name = "updated_by", nullable = false, length = 64)
    @Schema(description = "最近维护成员标识，由服务端拥有")
    private String updatedBy;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "初始为零、递增的安全整数CAS版本")
    private Long configVersion;

    @Column(name = "created_at", nullable = false)
    @Schema(description = "创建Unix毫秒")
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    @Schema(description = "最近维护Unix毫秒")
    private Long updatedAt;
}
