package com.gaiys.proto.prototypehub.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 单次历史线程授权及撤销审计；来源身份可已清理，原作者和原会话均不变更。仅永久项目/版本清理时删除。 */
@Getter
@Setter
@Table(
        name = "p_review_association",
        indexes = {
            @Index(
                    name = "idx_review_assoc_active",
                    columnList = "active_scope_key",
                    unique = true),
            @Index(
                    name = "idx_review_assoc_history",
                    columnList =
                            "project_id,version_id,share_id,revision_id,source_guest_id,create_time,id"),
            @Index(
                    name = "idx_review_assoc_target",
                    columnList = "target_guest_id,share_id,revision_id,revoked"),
            @Index(name = "idx_review_assoc_version", columnList = "version_id")
        })
@Schema(description = "同分享固定修订的可撤销历史线程授权，不合并访客身份")
public class PrototypeReviewAssociationEntity extends GenericEntity<String> {
    @JsonIgnore
    @Column(name = "active_scope_key", length = 64)
    @Schema(description = "未撤销来源范围唯一槽，撤销时置空")
    private String activeScopeKey;

    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "所属逻辑版本标识")
    private String versionId;

    @Column(name = "share_id", length = 64, nullable = false)
    @Schema(description = "原始分享标识")
    private String shareId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "固定不可变修订标识")
    private String revisionId;

    @Column(name = "source_thread_id", length = 64, nullable = false)
    @Schema(description = "人工选定的历史来源线程标识")
    private String sourceThreadId;

    @JsonIgnore
    @Column(name = "source_guest_id", length = 64, nullable = false)
    @Schema(description = "历史未验证访客标识，不依赖身份行存续")
    private String sourceGuestId;

    @Column(name = "source_verification_level", length = 16, nullable = false)
    @Schema(description = "来源身份命名空间，固定unverified")
    private String sourceVerificationLevel;

    @Column(name = "target_guest_id", length = 64, nullable = false)
    @Schema(description = "目标邮箱访客标识")
    private String targetGuestId;

    @Column(name = "target_verification_level", length = 16, nullable = false)
    @Schema(description = "目标身份命名空间，固定email")
    private String targetVerificationLevel;

    @Column(name = "share_config_version", nullable = false)
    @Schema(description = "授权时分享配置版本")
    private Long shareConfigVersion;

    @JsonIgnore
    @Column(name = "share_generation", length = 64, nullable = false)
    @Schema(description = "授权时分享密钥摘要，不公开")
    private String shareGeneration;

    @Column(name = "target_guest_config_version", nullable = false)
    @Schema(description = "授权时目标访客配置版本")
    private Long targetGuestConfigVersion;

    @Column(name = "evidence_reference", length = 1000, nullable = false)
    @Schema(description = "操作人记录的线下核实依据或记录编号")
    private String evidenceReference;

    @Column(name = "created_by", length = 64, nullable = false)
    @Schema(description = "授权操作人标识")
    private String createdBy;

    @Column(name = "created_by_name", length = 128, nullable = false)
    @Schema(description = "授权操作人显示名称快照")
    private String createdByName;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "授权时间，Unix毫秒")
    private Long createTime;

    @Column(name = "revoked", nullable = false)
    @Schema(description = "是否已显式撤销")
    private Boolean revoked;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "独立授权状态并发版本")
    private Long configVersion;

    @Column(name = "revoked_by", length = 64)
    @Schema(description = "撤销操作人标识")
    private String revokedBy;

    @Column(name = "revoked_by_name", length = 128)
    @Schema(description = "撤销操作人显示名称快照")
    private String revokedByName;

    @Column(name = "revoked_at")
    @Schema(description = "撤销时间，Unix毫秒")
    private Long revokedAt;

    @Column(name = "revoke_reason", length = 1000)
    @Schema(description = "撤销原因")
    private String revokeReason;
}
