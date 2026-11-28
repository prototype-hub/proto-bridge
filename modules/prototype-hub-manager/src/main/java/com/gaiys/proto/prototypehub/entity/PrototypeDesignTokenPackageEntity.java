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

/** 固定修订DTCG包；原字节不可变，来源声明独立CAS维护。 */
@Getter
@Setter
@Table(
        name = "p_design_token_package",
        indexes = {
            @Index(name = "idx_token_package_revision", columnList = "revision_id,created_at,id"),
            @Index(name = "idx_token_package_version", columnList = "version_id"),
            @Index(name = "idx_token_package_project", columnList = "project_id")
        })
@Schema(description = "固定修订私有DTCG规范包")
public class PrototypeDesignTokenPackageEntity implements Entity {
    @Id
    @GeneratedValue(generator = "default_id")
    @Column(name = "id", length = 64, updatable = false)
    private String id;

    @Column(name = "project_id", length = 64, nullable = false)
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    private String revisionId;

    @Column(name = "file_name", length = 255, nullable = false)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    @Column(name = "storage_path", length = 512, nullable = false)
    private String storagePath;

    @Column(name = "standard_version", length = 32, nullable = false)
    private String standardVersion;

    @Column(name = "validator_version", length = 64, nullable = false)
    private String validatorVersion;

    @Column(name = "valid", nullable = false)
    private Boolean valid;

    @Column(name = "token_count", nullable = false)
    private Integer tokenCount;

    @Column(name = "group_count", nullable = false)
    private Integer groupCount;

    @Column(name = "issue_count", nullable = false)
    private Integer issueCount;

    @Column(name = "report", nullable = false)
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private String report;

    @Column(name = "source_tool", length = 80)
    private String sourceTool;

    @Column(name = "source_name", length = 200)
    private String sourceName;

    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @Column(name = "source_notes", length = 2000)
    private String sourceNotes;

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
