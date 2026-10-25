package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 追加式纯文本评审消息；可见性由授权入口决定，作者仅取服务端身份。 */
@Getter
@Setter
@Table(
        name = "p_review_message",
        indexes = {
            @Index(name = "idx_review_message_version", columnList = "version_id"),
            @Index(name = "idx_review_message_project", columnList = "project_id"),
            @Index(
                    name = "idx_review_message_page",
                    columnList = "thread_id,visibility,create_time,id")
        })
@Schema(description = "追加式纯文本评审消息；可见性由授权入口决定，作者仅取服务端身份。")
public class PrototypeReviewMessageEntity extends GenericEntity<String> {
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

    @Column(name = "text", length = 4000, nullable = false)
    @Schema(description = "仅按纯文本显示的正文")
    private String text;

    @Column(name = "author_type", length = 16, nullable = false)
    @Schema(description = "作者类型：guest或member")
    private String authorType;

    @Column(name = "author_name", length = 128, nullable = false)
    @Schema(description = "服务端作者显示名称快照")
    private String authorName;

    @Column(name = "author_company", length = 200)
    @Schema(description = "访客自述公司，内部成员为空")
    private String authorCompany;

    @Column(name = "verification_level", length = 16)
    @Schema(description = "访客身份等级，内部成员为空")
    private String verificationLevel;

    @Column(name = "visibility", length = 16, nullable = false)
    @Schema(description = "消息可见性：public或internal")
    private String visibility;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "创建Unix毫秒")
    private Long createTime;
}
