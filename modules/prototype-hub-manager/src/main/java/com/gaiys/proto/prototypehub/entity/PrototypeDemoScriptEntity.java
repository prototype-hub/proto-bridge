package com.gaiys.proto.prototypehub.entity;

import com.gaiys.proto.prototypehub.web.info.DemoScriptStep;
import io.swagger.v3.oas.annotations.media.Schema;
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

/** 固定修订的人工演示脚本；整份正文可被显式开放给现有分享读者，不执行或复制原型。 */
@Getter
@Setter
@Table(
        name = "p_demo_script",
        indexes = {
            @Index(name = "idx_demo_script_revision", columnList = "revision_id,created_at,id"),
            @Index(
                    name = "idx_demo_script_shared",
                    columnList = "revision_id,share_enabled,created_at,id"),
            @Index(name = "idx_demo_script_version", columnList = "version_id"),
            @Index(name = "idx_demo_script_project", columnList = "project_id")
        })
@Schema(description = "固定修订人工任务演示脚本")
public class PrototypeDemoScriptEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "所属逻辑版本标识")
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "不可变修订标识，不随当前修订迁移")
    private String revisionId;

    @Column(length = 120, nullable = false)
    @Schema(description = "演示标题，普通文本")
    private String title;

    @Column(length = 2000, nullable = false)
    @Schema(description = "必填演示目标，普通文本")
    private String objective;

    @Column(length = 2000, nullable = false)
    @Schema(description = "演示前置条件，未填写为空串")
    private String prerequisites;

    @Column(name = "reset_note", length = 2000, nullable = false)
    @Schema(description = "人工重置指引，不执行自动重置")
    private String resetNote;

    @Column(nullable = false)
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "一至五十个有序步骤，可重复访问同一页面")
    private List<DemoScriptStep> steps;

    @Column(name = "share_enabled", nullable = false)
    @Schema(description = "显式允许现有同修订分享读者读取完整正文")
    private Boolean shareEnabled;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "从零递增的安全整数CAS版本")
    private Long configVersion;

    @Column(name = "created_at", nullable = false)
    @Schema(description = "创建Unix毫秒")
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    @Schema(description = "最近维护Unix毫秒")
    private Long updatedAt;
}
