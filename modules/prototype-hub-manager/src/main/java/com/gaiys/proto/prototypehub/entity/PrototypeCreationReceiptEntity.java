package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 与资源创建原子提交的请求回执；主键仅保存用户、动作、作用域和请求键的摘要。 */
@Getter
@Setter
@Table(name = "p_creation_receipt")
@Schema(description = "原型资源创建幂等回执")
public class PrototypeCreationReceiptEntity extends GenericEntity<String> {
    @Column(name = "revision_id", length = 64)
    @Schema(description = "固定内容快照标识；旧数据需显式迁移，短会话为空时拒绝访问")
    private String revisionId;

    @Column(name = "fingerprint", length = 64, nullable = false)
    @Schema(description = "规范化创建内容的 SHA-256 摘要")
    private String fingerprint;

    @Column(name = "resource_id", length = 64, nullable = false)
    @Schema(description = "首次创建成功的资源标识")
    private String resourceId;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "回执创建时间（Unix 毫秒）")
    private Long createTime;
}
