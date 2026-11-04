package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 固定修订页面的人工视口自测记录；结论和证据均由成员填写，不代表自动测试或真实设备认证。 */
@Getter
@Setter
@Table(
        name = "p_viewport_check",
        indexes = {
            @Index(
                    name = "idx_viewport_check_page",
                    columnList = "revision_id,page_id,created_at,id"),
            @Index(name = "idx_viewport_check_version", columnList = "version_id"),
            @Index(name = "idx_viewport_check_project", columnList = "project_id")
        })
@Schema(description = "固定修订页面的人工视口自测记录")
public class PrototypeViewportCheckEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "所属逻辑版本标识")
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "固定修订标识，不随当前修订变化")
    private String revisionId;

    @Column(name = "page_id", length = 64, nullable = false)
    @Schema(description = "清单中的稳定页面标识")
    private String pageId;

    @Column(name = "viewport_width", nullable = false)
    @Schema(description = "CSS视口宽度，单位为CSS像素")
    private Integer viewportWidth;

    @Column(name = "viewport_height", nullable = false)
    @Schema(description = "CSS视口高度，单位为CSS像素")
    private Integer viewportHeight;

    @Column(name = "viewport_scale", nullable = false)
    @Schema(description = "舞台显示缩放百分比，不改变CSS视口或设备DPR")
    private Integer viewportScale;

    @Column(length = 16, nullable = false)
    @Schema(description = "人工场景类别的稳定标识")
    private String category;

    @Column(length = 120, nullable = false)
    @Schema(description = "人工场景标题，纯文本")
    private String title;

    @Column(length = 2000, nullable = false)
    @Schema(description = "人工操作步骤，纯文本；空串表示未填写")
    private String steps;

    @Column(length = 2000, nullable = false)
    @Schema(description = "人工填写的预期结果，纯文本")
    private String expected;

    @Column(length = 2000, nullable = false)
    @Schema(description = "人工观察；通过或不通过时必须填写")
    private String observed;

    @Column(length = 16, nullable = false)
    @Schema(description = "人工结论untested、passed或failed，不是自动测试结果")
    private String result;

    @Column(length = 2000, nullable = false)
    @Schema(description = "可选人工纯文本证据，不包含附件或自动截图")
    private String evidence;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "从零递增的并发版本")
    private Long configVersion;

    @Column(name = "created_at", nullable = false)
    @Schema(description = "创建Unix毫秒")
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    @Schema(description = "最近维护Unix毫秒")
    private Long updatedAt;
}
