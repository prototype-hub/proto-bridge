package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 与评审写入原子提交的领域outbox；无正文、邮箱、凭据或业务投递任务。 */
@Getter
@Setter
@Table(
        name = "p_review_event",
        indexes = {
            @Index(name = "idx_review_event_version", columnList = "version_id"),
            @Index(name = "idx_review_event_project", columnList = "project_id"),
            @Index(name = "idx_review_event_retention", columnList = "create_time,id")
        })
@Schema(description = "与评审写入原子提交的领域outbox；无正文、邮箱、凭据或业务投递任务。")
public class PrototypeReviewEventEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "所属逻辑版本标识")
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "不可变修订标识")
    private String revisionId;

    @Column(name = "thread_id", length = 64, nullable = false)
    @Schema(description = "所属线程标识")
    private String threadId;

    @Column(name = "message_id", length = 64)
    @Schema(description = "相关消息标识，纯状态事件为空")
    private String messageId;

    @Column(name = "event_type", length = 32, nullable = false)
    @Schema(description = "领域事件类型")
    private String eventType;

    @Column(name = "actor_type", length = 16, nullable = false)
    @Schema(description = "触发者类型：guest或member")
    private String actorType;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "事件发生Unix毫秒，保留三十天")
    private Long createTime;
}
