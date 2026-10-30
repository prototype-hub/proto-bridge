package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 单次外层分享打开的最小持久事件；不保存访客身份、IP、分享令牌或访问凭据。 */
@Getter
@Setter
@Table(
        name = "p_share_visit",
        indexes = {
            @Index(name = "idx_share_visit_day_project", columnList = "visit_day,project_id"),
            @Index(name = "idx_share_visit_version", columnList = "version_id")
        })
@Schema(description = "保留35个自然日的幂等外层分享访问事件")
public class PrototypeShareVisitEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "服务端校验分享后推导的项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "访问的逻辑版本标识")
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "实际访问的固定修订标识，不随最新版本迁移")
    private String revisionId;

    @Column(name = "share_id", length = 64, nullable = false)
    @Schema(description = "分享记录标识，不是公开分享令牌")
    private String shareId;

    @Column(name = "visit_day", length = 10, nullable = false)
    @Schema(description = "首次接收时的Asia/Shanghai自然日，格式yyyy-MM-dd")
    private String visitDay;

    @Column(name = "received_at", nullable = false)
    @Schema(description = "服务端首次接收Unix毫秒，不信任客户端时间")
    private Long receivedAt;
}
