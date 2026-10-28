package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 固定修订页面的人工工作项引用；不抓取外部内容，不赋予外部或项目权限。 */
@Getter
@Setter
@Table(
        name = "p_work_item",
        indexes = {
            @Index(name = "idx_work_item_page", columnList = "revision_id,page_id,created_at,id"),
            @Index(name = "idx_work_item_version", columnList = "version_id")
        })
@Schema(description = "固定修订页面的人工工作项引用")
public class PrototypeWorkItemEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "所属逻辑版本标识")
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "固定修订标识，不随版本更新自动迁移")
    private String revisionId;

    @Column(name = "page_id", length = 64, nullable = false)
    @Schema(description = "清单中的稳定页面标识")
    private String pageId;

    @Column(name = "url", length = 2048, nullable = false)
    @Schema(description = "手工填写的HTTP或HTTPS工作项地址，无账号信息")
    private String url;

    @Column(name = "note", length = 2000, nullable = false)
    @Schema(description = "纯文本业务备注，不执行HTML")
    private String note;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "初始为零、更新递增的并发版本")
    private Long configVersion;

    @Column(name = "created_at", nullable = false)
    @Schema(description = "创建Unix毫秒")
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    @Schema(description = "最近更新Unix毫秒")
    private Long updatedAt;
}
