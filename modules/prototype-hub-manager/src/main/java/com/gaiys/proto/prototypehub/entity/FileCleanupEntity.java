package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

@Getter
@Setter
@Table(
        name = "p_file_cleanup",
        indexes = @Index(name = "idx_protohub_cleanup_due", columnList = "next_attempt"))
@Schema(description = "原型上传候选清理重试及来源不明旧任务的保留记录")
public class FileCleanupEntity extends GenericEntity<String> {
    @Column(name = "resource_type", length = 16, nullable = false)
    @Schema(description = "资源类型：upload-candidate 可核验候选；旧 project/version 来源不明，仅保留不执行")
    private String resourceType;

    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "待清理文件所属的原型项目标识")
    private String projectId;

    @Column(name = "version_id", length = 128)
    @Schema(description = "待清理原型版本标识，项目级清理时为空")
    private String versionId;

    @Column(name = "storage_path", length = 1024)
    @Schema(description = "待清理版本的存储相对路径")
    private String storagePath;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "清理任务创建时间（Unix 毫秒）")
    private Long createTime;

    @Column(name = "next_attempt", nullable = false)
    @Schema(description = "下次清理尝试时间（Unix 毫秒，0 表示立即尝试）")
    private Long nextAttempt;

    @Column(nullable = false)
    @Schema(description = "已失败的清理尝试次数")
    private Integer attempts;
}
