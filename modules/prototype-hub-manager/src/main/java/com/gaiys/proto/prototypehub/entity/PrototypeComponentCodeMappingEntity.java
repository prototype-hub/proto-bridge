package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 成员维护的固定页面组件代码卡；仓库路径和片段仅是文本，不触发磁盘、网络或代码执行。 */
@Getter
@Setter
@Table(
        name = "p_component_code_mapping",
        indexes = {
            @Index(
                    name = "idx_code_mapping_page",
                    columnList = "revision_id,page_id,created_at,id"),
            @Index(name = "idx_code_mapping_version", columnList = "version_id")
        })
@Schema(description = "固定页面组件代码映射卡")
public class PrototypeComponentCodeMappingEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    private String revisionId;

    @Column(name = "page_id", length = 64, nullable = false)
    private String pageId;

    @Column(name = "alias", length = 120, nullable = false)
    private String alias;

    @Column(name = "repository_path", length = 2048, nullable = false)
    private String repositoryPath;

    @Column(name = "repository_url", length = 2048, nullable = false)
    private String repositoryUrl;

    @Column(name = "documentation_url", length = 2048, nullable = false)
    private String documentationUrl;

    @Column(name = "snippet", length = 16000, nullable = false)
    private String snippet;

    @Column(name = "framework", length = 120, nullable = false)
    private String framework;

    @Column(name = "framework_version", length = 120, nullable = false)
    private String frameworkVersion;

    @Column(name = "description_id", length = 128, nullable = true)
    private String descriptionId;

    @Column(name = "description_version", nullable = true)
    private Long descriptionVersion;

    @Column(name = "config_version", nullable = false)
    private Long configVersion;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
