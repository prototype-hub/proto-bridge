package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 短期内部预览授权；主键为随机凭证的 SHA-256，数据库不保存可直接使用的凭证。 */
@Getter
@Setter
@Table(
        name = "p_preview_session",
        indexes = {
            @Index(name = "idx_preview_session_subject", columnList = "user_id,version_id"),
            @Index(name = "idx_preview_session_expiry", columnList = "expires_at"),
            @Index(
                    name = "idx_preview_session_project_expiry",
                    columnList = "project_id,expires_at")
        })
@Schema(description = "原型内部预览短期会话摘要及授权绑定")
public class PrototypePreviewSessionEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "授权项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "授权逻辑版本标识")
    private String versionId;

    @Column(name = "user_id", length = 64, nullable = false)
    @Schema(description = "获取内部预览授权的用户标识")
    private String userId;

    @Column(name = "storage_path", length = 1024, nullable = false)
    @Schema(description = "签发时版本文件路径，替换后旧授权失效")
    private String storagePath;

    @Column(name = "access_epoch", length = 64, nullable = false)
    @Schema(description = "签发时项目授权代次，撤员及重新加入不复活旧授权")
    private String accessEpoch;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "签发时间（Unix 毫秒）")
    private Long createTime;

    @Column(name = "expires_at", nullable = false)
    @Schema(description = "到期时间（Unix 毫秒）")
    private Long expiresAt;
}
