package com.gaiys.proto.prototypehub.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.JDBCType;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 已校验并发布的不可变文件快照；只允许创建和随逻辑版本整体删除，不提供更新入口。 */
@Getter
@Setter
@Table(
        name = "p_revision",
        indexes = {
            @Index(name = "idx_revision_version_order", columnList = "version_id,create_time,id"),
            @Index(name = "idx_revision_storage", columnList = "storage_path", unique = true),
            @Index(name = "idx_revision_project", columnList = "project_id")
        })
@Schema(description = "原型逻辑版本的不可变内容快照及文件清单")
public class PrototypeRevisionEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "快照所属项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "快照所属逻辑版本标识")
    private String versionId;

    @Column(name = "version_name", length = 128, nullable = false)
    @Schema(description = "发布时的版本名称快照")
    private String versionName;

    @Column(length = 2000)
    @Schema(description = "发布时的版本说明快照")
    private String description;

    @JsonIgnore
    @Column(name = "storage_path", length = 1024, nullable = false)
    @Schema(description = "本快照独占的文件存储相对路径，发布后不可修改或复用")
    private String storagePath;

    @Column(name = "entry_file", length = 1024, nullable = false)
    @Schema(description = "本快照入口文件的规范化相对路径")
    private String entryFile;

    @JsonIgnore
    @Column(name = "manifest_json", nullable = false)
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "规范化文件与页面清单的固定字段顺序 JSON，不包含快照标识或时间")
    private String manifestJson;

    @Column(name = "manifest_sha256", length = 64, nullable = false)
    @Schema(description = "规范化清单 UTF-8 字节的 SHA-256")
    private String manifestSha256;

    @Column(name = "file_count", nullable = false)
    @Schema(description = "清单中的普通文件数量")
    private Integer fileCount;

    @Column(name = "total_bytes", nullable = false)
    @Schema(description = "HTML 检查脚本注入后实际文件的总字节数")
    private Long totalBytes;

    @Column(name = "creator_id", length = 64, nullable = false)
    @Schema(description = "快照发布用户标识，迁移时保留逻辑版本创建用户")
    private String creatorId;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "快照发布时间（Unix 毫秒）")
    private Long createTime;
}
