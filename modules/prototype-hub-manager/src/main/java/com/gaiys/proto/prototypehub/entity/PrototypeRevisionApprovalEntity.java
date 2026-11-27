package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 固定修订正式批准/发布的只追加决策；每行保存完整结果，最新序号即当前状态。 不复制原型文件或改变配额，历史随版本永久清理；不提供通用修改、删除入口。 */
@Getter
@Setter
@Table(
        name = "p_revision_approval",
        indexes = {
            @Index(
                    name = "idx_revision_approval_sequence",
                    columnList = "revision_id,config_version",
                    unique = true),
            @Index(
                    name = "idx_revision_approval_scope",
                    columnList = "version_id,revision_id,config_version"),
            @Index(name = "idx_revision_approval_project", columnList = "project_id")
        })
@Schema(description = "固定修订正式批准与发布决策历史")
public class PrototypeRevisionApprovalEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    private String revisionId;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "同一修订内从一递增的决策序号；零表示尚无正式决策")
    private Long configVersion;

    @Column(length = 16, nullable = false)
    @Schema(description = "显式approve/revoke/publish/withdraw动作")
    private String action;

    @Column(nullable = false)
    private Boolean approved;

    @Column(nullable = false)
    private Boolean published;

    @Column(name = "approval_id", length = 64)
    @Schema(description = "当前有效批准决策，同一固定范围内引用；未批准为null")
    private String approvalId;

    @Column(name = "publication_id", length = 64)
    @Schema(description = "当前有效发布决策，同一固定范围内引用；未发布为null")
    private String publicationId;

    @Column(name = "actor_id", length = 64, nullable = false)
    private String actorId;

    @Column(name = "actor_name", length = 128, nullable = false)
    @Schema(description = "决策时当前账号姓名快照，后续撤员不改历史")
    private String actorName;

    @Column(length = 2000, nullable = false)
    @Schema(description = "可选纯文本说明，空值规范为空串")
    private String note;

    @Column(name = "create_time", nullable = false)
    private Long createTime;
}
