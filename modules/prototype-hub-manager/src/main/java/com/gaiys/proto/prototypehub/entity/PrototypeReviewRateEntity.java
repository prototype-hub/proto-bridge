package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 跨实例邮箱验证发送限频窗口。 */
@Getter
@Setter
@Table(
        name = "p_review_rate",
        indexes = {
            @Index(name = "idx_p_review_rate_project", columnList = "project_id"),
            @Index(name = "idx_p_review_rate_expiry", columnList = "expires_at")
        })
@Schema(description = "跨实例邮箱验证发送限频窗口")
public class PrototypeReviewRateEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "window_start", nullable = false)
    @Schema(description = "一小时固定窗口起点（Unix毫秒）")
    private Long windowStart;

    @Column(name = "send_count", nullable = false)
    @Schema(description = "本窗口已占用发送次数，失败仍计数")
    private Integer sendCount;

    @Column(name = "next_allowed_at", nullable = false)
    @Schema(description = "邮箱下次允许发送时间（Unix毫秒）")
    private Long nextAllowedAt;

    @Column(name = "expires_at", nullable = false)
    @Schema(description = "窗口失效清理时间（Unix毫秒）")
    private Long expiresAt;
}
