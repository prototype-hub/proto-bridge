package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 页面级客户文本线程；作者与页面快照不可变，状态版本不受内部备注影响。 */
@Getter
@Setter
@Table(
        name = "p_review_thread",
        indexes = {
            @Index(name = "idx_review_thread_version", columnList = "version_id"),
            @Index(name = "idx_review_thread_project", columnList = "project_id"),
            @Index(name = "idx_review_thread_assignee", columnList = "assignee_id,project_id,id"),
            @Index(
                    name = "idx_review_thread_scope",
                    columnList =
                            "revision_id,page_id,share_id,guest_id,verification_level,public_updated_at,id")
        })
@Schema(description = "页面级客户文本线程；作者与页面快照不可变，状态版本不受内部备注影响。")
public class PrototypeReviewThreadEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "所属逻辑版本标识")
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "不可变修订标识")
    private String revisionId;

    @Column(name = "share_id", length = 64, nullable = false)
    @Schema(description = "首次投稿的分享标识")
    private String shareId;

    @Column(name = "page_id", length = 64, nullable = false)
    @Schema(description = "清单中的稳定页面标识")
    private String pageId;

    @Column(name = "page_name", length = 1024, nullable = false)
    @Schema(description = "服务器清单页面名称快照")
    private String pageName;

    @Column(name = "page_path", length = 1024, nullable = false)
    @Schema(description = "服务器清单页面路径快照")
    private String pagePath;

    @Column(name = "guest_id", length = 64, nullable = false)
    @Schema(description = "服务端会话解析的作者标识，不对外公开")
    private String guestId;

    @Column(name = "verification_level", length = 16, nullable = false)
    @Schema(description = "作者身份命名空间：email或unverified")
    private String verificationLevel;

    @Column(name = "author_name", length = 100, nullable = false)
    @Schema(description = "首次作者自述姓名快照")
    private String authorName;

    @Column(name = "author_company", length = 200)
    @Schema(description = "首次作者自述公司快照")
    private String authorCompany;

    @Column(name = "summary", length = 200, nullable = false)
    @Schema(description = "首条公开文本的不可变摘要")
    private String summary;

    @Column(name = "state", length = 16, nullable = false)
    @Schema(description = "线程状态：open或resolved")
    private String state;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "仅状态变更递增的并发版本")
    private Long configVersion;

    @Column(name = "assignee_id", length = 64)
    @Schema(description = "内部当前负责人用户标识，不授予访问权限")
    private String assigneeId;

    @Column(name = "assignee_name", length = 128)
    @Schema(description = "分派时负责人姓名快照，不随账号改名更新")
    private String assigneeName;

    @Column(name = "assignment_version")
    @Schema(description = "独立分派并发版本，历史空值按零读取")
    private Long assignmentVersion;

    @Column(name = "assignment_updated_at")
    @Schema(description = "最近分派或清除Unix毫秒，不影响公开更新时间")
    private Long assignmentUpdatedAt;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "创建Unix毫秒")
    private Long createTime;

    @Column(name = "public_updated_at", nullable = false)
    @Schema(description = "最近公开消息或状态变更Unix毫秒，不受内部备注影响")
    private Long publicUpdatedAt;
}
