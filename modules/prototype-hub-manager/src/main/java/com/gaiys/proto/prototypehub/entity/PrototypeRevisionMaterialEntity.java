package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.JDBCType;
import javax.persistence.Column;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.web.api.crud.entity.Entity;

/** 固定修订的私有资料及成员声明来源；不改变原始清单或认证来源。 */
@Getter
@Setter
@Table(
        name = "p_revision_material",
        indexes = {
            @Index(
                    name = "idx_revision_material_revision",
                    columnList = "revision_id,created_at,id"),
            @Index(name = "idx_revision_material_version", columnList = "version_id"),
            @Index(name = "idx_revision_material_project", columnList = "project_id")
        })
@Schema(description = "固定修订私有资料与人工来源声明")
public class PrototypeRevisionMaterialEntity implements Entity {
    // 显式保留hsweb默认主键映射，让本表Schema提供中文主键描述。
    @Id
    @GeneratedValue(generator = "default_id")
    @Column(name = "id", length = 64, updatable = false)
    @Schema(description = "资料或来源声明唯一标识，不可复用")
    private String id;

    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "所属逻辑版本标识")
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "固定修订标识，不随当前版本变化")
    private String revisionId;

    @Column(name = "material_type", length = 16, nullable = false)
    @Schema(description = "资料类别prototype、image、document或无文件link")
    private String materialType;

    // link沿既有NOT NULL表使用空串/0表示无文件；对外不投影成文件名、摘要或路径。
    @Column(name = "file_name", length = 255, nullable = false)
    @Schema(description = "原始文件名称，仅作显示和下载名称")
    private String fileName;

    @Column(name = "media_type", length = 128, nullable = false)
    @Schema(description = "服务端校验得到的媒体类型")
    private String mediaType;

    @Column(name = "file_size", nullable = false)
    @Schema(description = "原始文件字节数")
    private Long fileSize;

    @Column(name = "sha256", length = 64, nullable = false)
    @Schema(description = "原始文件SHA256摘要")
    private String sha256;

    @Column(name = "storage_path", length = 512, nullable = false)
    @Schema(description = "服务端私有规范路径，不向客户端返回")
    private String storagePath;

    @Column(name = "source_tool", length = 80)
    @Schema(description = "成员声明的来源工具，不代表认证来源")
    private String sourceTool;

    @Column(name = "source_name", length = 200)
    @Schema(description = "成员声明的来源名称")
    private String sourceName;

    @Column(name = "source_url", length = 2048)
    @Schema(description = "成员声明的HTTP或HTTPS来源地址，服务端不抓取")
    private String sourceUrl;

    @Column(name = "source_notes", length = 2000)
    @Schema(description = "成员填写的来源备注，纯文本")
    private String sourceNotes;

    @Column(name = "text_status", length = 24)
    @Schema(description = "派生正文状态；旧文档空值表示尚未提取")
    private String textStatus;

    @Column(name = "text_content")
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "有界派生纯文本，与原文件同生命周期，不在资料列表返回")
    private String textContent;

    @Column(name = "text_character_count")
    @Schema(description = "成功提取正文的Unicode码点数量")
    private Integer textCharacterCount;

    @Column(name = "text_extracted_at")
    @Schema(description = "本次正文提取完成Unix毫秒；旧资料为空")
    private Long textExtractedAt;

    @Column(name = "text_extractor_version", length = 64)
    @Schema(description = "完成提取的解析规则版本；旧资料为空")
    private String textExtractorVersion;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "从零递增的来源维护并发版本")
    private Long configVersion;

    @Column(name = "created_by", length = 64, nullable = false)
    @Schema(description = "创建成员标识")
    private String createdBy;

    @Column(name = "updated_by", length = 64, nullable = false)
    @Schema(description = "最近维护成员标识")
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    @Schema(description = "创建Unix毫秒")
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    @Schema(description = "最近维护Unix毫秒")
    private Long updatedAt;
}
