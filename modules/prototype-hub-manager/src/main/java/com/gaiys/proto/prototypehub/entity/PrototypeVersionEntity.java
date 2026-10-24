package com.gaiys.proto.prototypehub.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

@Getter
@Setter
@Table(
        name = "p_version",
        indexes = {
            @Index(name = "idx_protohub_version_project", columnList = "project_id"),
            @Index(name = "idx_version_deleted", columnList = "deleted_time,id")
        })
@EnableEntityEvent
@Schema(description = "原型项目版本及上传文件信息")
public class PrototypeVersionEntity extends GenericEntity<String> {
    @Column(name = "deleted_time")
    @Schema(description = "软删除时间（Unix 毫秒），为空表示活动逻辑版本；30天后永久清理修订和文件")
    private Long deletedTime;

    @Column(name = "current_revision_id", length = 64)
    @Schema(description = "当前不可变快照标识；为空时必须显式迁移旧内容")
    private String currentRevisionId;

    @Transient
    @JsonIgnore
    @Schema(description = "仅供幂等响应选择原成功快照，不参与数据库映射")
    private String responseRevisionId;

    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "版本所属的原型项目标识")
    private String projectId;

    @Column(name = "version_name", length = 128, nullable = false)
    @Schema(description = "原型版本名称")
    private String versionName;

    @Column(name = "description", length = 2000)
    @Schema(description = "原型版本说明")
    private String description;

    @JsonIgnore
    @Column(name = "storage_path", length = 1024, nullable = false)
    @Schema(description = "原型版本文件的存储相对路径")
    private String storagePath;

    @Column(name = "entry_file", length = 1024, nullable = false)
    @Schema(description = "原型页面入口文件的相对路径")
    private String entryFile;

    @JsonIgnore
    @Column(name = "preview_key", length = 64, nullable = false)
    @Schema(description = "内部预览文件访问密钥")
    private String previewKey;

    @Column(name = "status", length = 255, nullable = false)
    @Schema(description = "原型版本状态，上传完成后为 ready")
    private String status;

    @Column(name = "creator_id", length = 64, nullable = false)
    @Schema(description = "版本创建者用户标识")
    private String creatorId;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "版本创建时间（Unix 毫秒）")
    private Long createTime;
}
