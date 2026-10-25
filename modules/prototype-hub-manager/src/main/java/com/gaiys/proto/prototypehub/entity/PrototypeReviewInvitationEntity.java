package com.gaiys.proto.prototypehub.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 单次定向邮箱邀请，不能替代分享访问证明或邮箱OTP。 */
@Getter
@Setter
@Table(
        name = "p_review_invitation",
        indexes = {
            @Index(name = "idx_review_invitation_share", columnList = "share_id"),
            @Index(name = "idx_review_invitation_project", columnList = "project_id"),
            @Index(name = "idx_review_invitation_version", columnList = "version_id"),
            @Index(name = "idx_review_invitation_expiry", columnList = "expires_at"),
            @Index(name = "idx_review_invitation_token", columnList = "token_hash", unique = true)
        })
@Schema(description = "单次固定分享邮箱邀请")
public class PrototypeReviewInvitationEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "所属版本标识")
    private String versionId;

    @Column(name = "share_id", length = 64, nullable = false)
    @Schema(description = "所属分享标识")
    private String shareId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "固定修订标识")
    private String revisionId;

    @Column(name = "share_config_version", nullable = false)
    @Schema(description = "签发时分享配置版本")
    private Long shareConfigVersion;

    @JsonIgnore
    @Column(name = "share_generation", length = 64, nullable = false)
    @Schema(description = "分享密钥摘要")
    private String shareGeneration;

    @JsonIgnore
    @Column(name = "token_hash", length = 64, nullable = false)
    @Schema(description = "邀请凭据摘要，不保存原始凭据")
    private String tokenHash;

    @JsonIgnore
    @Column(name = "email", length = 254, nullable = false)
    @Schema(description = "规范收件邮箱，仅服务端使用")
    private String email;

    @Column(name = "guest_config_version", nullable = false)
    @Schema(description = "访客状态版本，历史空值或无访客为零")
    private Long guestConfigVersion;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "邀请状态并发版本")
    private Long configVersion;

    @Column(name = "revoked", nullable = false)
    @Schema(description = "是否已被撤销")
    private Boolean revoked;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "创建时间（Unix毫秒）")
    private Long createTime;

    @Column(name = "expires_at", nullable = false)
    @Schema(description = "失效时间，最长24小时且不晚于分享到期")
    private Long expiresAt;

    @Column(name = "consumed_at")
    @Schema(description = "成功消费时间，未消费为空")
    private Long consumedAt;
}
