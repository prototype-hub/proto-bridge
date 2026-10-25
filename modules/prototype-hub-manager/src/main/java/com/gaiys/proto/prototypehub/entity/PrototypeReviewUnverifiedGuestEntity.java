package com.gaiys.proto.prototypehub.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 分享明确允许的短期未验证访客；无邮箱、不可找回，不与已验证身份合并。 */
@Getter
@Setter
@Table(
        name = "p_review_unverified_guest",
        indexes = {
            @Index(name = "idx_p_review_unverified_project", columnList = "project_id"),
            @Index(name = "idx_p_review_unverified_expiry", columnList = "expires_at")
        })
@Schema(description = "短期未验证访客，仅保存自述资料，不属于平台用户")
public class PrototypeReviewUnverifiedGuestEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @JsonIgnore
    @Column(name = "display_name", length = 100, nullable = false)
    @Schema(description = "未经核验的自述名称")
    private String displayName;

    @JsonIgnore
    @Column(name = "company", length = 200)
    @Schema(description = "未经核验的自述公司")
    private String company;

    @Column(name = "enabled", nullable = false)
    @Schema(description = "是否启用本次访客身份")
    private Boolean enabled;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "访客启停并发版本")
    private Long configVersion;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "创建时间（Unix毫秒）")
    private Long createTime;

    @Column(name = "modify_time", nullable = false)
    @Schema(description = "最近状态修改时间（Unix毫秒）")
    private Long modifyTime;

    @Column(name = "expires_at", nullable = false)
    @Schema(description = "固定到期时间（创建后八小时，不续期）")
    private Long expiresAt;
}
