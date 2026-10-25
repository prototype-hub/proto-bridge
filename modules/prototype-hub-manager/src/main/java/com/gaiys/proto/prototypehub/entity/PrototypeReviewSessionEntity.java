package com.gaiys.proto.prototypehub.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 项目访客的短期分享会话，保障等级决定身份仓库，主键仅存Cookie摘要。 */
@Getter
@Setter
@Table(
        name = "p_review_session",
        indexes = {
            @Index(name = "idx_p_review_session_project", columnList = "project_id"),
            @Index(name = "idx_p_review_session_expiry", columnList = "expires_at"),
            @Index(name = "idx_p_review_session_version", columnList = "version_id")
        })
@Schema(description = "访客的短期分享会话，主键仅存Cookie摘要")
public class PrototypeReviewSessionEntity extends GenericEntity<String> {
    @Column(name = "verification_level", length = 16)
    @Schema(description = "身份保障等级：email或unverified，历史空值按email处理")
    private String verificationLevel;

    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "所属逻辑版本标识")
    private String versionId;

    @Column(name = "share_id", length = 64, nullable = false)
    @Schema(description = "授权分享标识")
    private String shareId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "固定修订标识")
    private String revisionId;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "分享配置并发版本")
    private Long configVersion;

    @JsonIgnore
    @Column(name = "share_generation", length = 64, nullable = false)
    @Schema(description = "签发时分享密钥摘要")
    private String shareGeneration;

    @Column(name = "guest_id", length = 64, nullable = false)
    @Schema(description = "项目访客标识，按保障等级引用对应身份仓库")
    private String guestId;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "签发时间（Unix毫秒）")
    private Long createTime;

    @Column(name = "expires_at", nullable = false)
    @Schema(description = "固定到期时间（Unix毫秒）")
    private Long expiresAt;
}
