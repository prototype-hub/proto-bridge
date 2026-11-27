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
        name = "p_page_relation",
        indexes = {
            @Index(
                    name = "idx_page_relation_out",
                    columnList = "revision_id,from_page_id,created_at,id"),
            @Index(
                    name = "idx_page_relation_in",
                    columnList = "revision_id,to_page_id,created_at,id"),
            @Index(name = "idx_page_relation_version", columnList = "version_id"),
            @Index(name = "idx_page_relation_project", columnList = "project_id")
        })
@Schema(description = "固定修订页面人工追踪元数据")
public class PrototypePageRelationEntity extends GenericEntity<String> {
    @Column(name = "project_id", nullable = false, length = 64)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", nullable = false, length = 64)
    @Schema(description = "所属逻辑版本标识")
    private String versionId;

    @Column(name = "revision_id", nullable = false, length = 64)
    @Schema(description = "不可变修订标识，不随当前修订迁移")
    private String revisionId;

    @Column(name = "from_page_id", nullable = false, length = 128)
    @Schema(description = "人工有向关系的起点真实页面标识")
    private String fromPageId;

    @Column(name = "to_page_id", nullable = false, length = 128)
    @Schema(description = "人工有向关系的终点真实页面标识")
    private String toPageId;

    @Column(name = "label", nullable = false, length = 120)
    @Schema(description = "人工关系名称，普通文本")
    private String label;

    @Column(name = "note", nullable = false, length = 2000)
    @Schema(description = "人工关系说明，未填写为空串")
    private String note;

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
