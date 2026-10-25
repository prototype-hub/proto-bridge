package com.gaiys.proto.prototypehub.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 一次性邮箱验证码及固定授权绑定。 */
@Getter
@Setter
@Table(
        name = "p_review_challenge",
        indexes = {
            @Index(name = "idx_p_review_challenge_project", columnList = "project_id"),
            @Index(name = "idx_p_review_challenge_expiry", columnList = "expires_at"),
            @Index(name = "idx_p_review_challenge_version", columnList = "version_id"),
            @Index(name = "idx_review_challenge_email", columnList = "project_id,email")
        })
@Schema(description = "一次性邮箱验证码及固定授权绑定")
public class PrototypeReviewChallengeEntity extends GenericEntity<String> {
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

    @JsonIgnore
    @Column(name = "cookie_hash", length = 64, nullable = false)
    @Schema(description = "匿名HttpOnly凭证摘要")
    private String cookieHash;

    @JsonIgnore
    @Column(name = "email", length = 254, nullable = false)
    @Schema(description = "规范邮箱，仅用于验证通知与身份匹配")
    private String email;

    @JsonIgnore
    @Column(name = "display_name", length = 100, nullable = false)
    @Schema(description = "本次自述显示名称")
    private String displayName;

    @JsonIgnore
    @Column(name = "company", length = 200)
    @Schema(description = "本次自述公司")
    private String company;

    @JsonIgnore
    @Column(name = "code_hash", length = 100, nullable = false)
    @Schema(description = "六位验证码的BCrypt摘要")
    private String codeHash;

    @JsonIgnore
    @Column(name = "invitation_id", length = 43)
    @Schema(description = "可选邀请记录标识，普通OTP为空，不保存原始凭据")
    private String invitationId;

    @Column(name = "delivered", nullable = false)
    @Schema(description = "SMTP已明确接受后才可验证")
    private Boolean delivered;

    @Column(name = "attempts", nullable = false)
    @Schema(description = "已提交的错误验证次数")
    private Integer attempts;

    @Column(name = "consumed", nullable = false)
    @Schema(description = "已消费或被更新挑战作废")
    private Boolean consumed;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "生成时间（Unix毫秒）")
    private Long createTime;

    @Column(name = "expires_at", nullable = false)
    @Schema(description = "失效时间（Unix毫秒）")
    private Long expiresAt;
}
