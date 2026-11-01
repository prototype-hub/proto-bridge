package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 固定修订页面首次提取结果；空结果和预算失败同样持久化，重提不覆盖人工维护。 */
@Getter
@Setter
@Table(
        name = "p_copy_snapshot",
        indexes = {
            @Index(
                    name = "idx_copy_snapshot_page",
                    columnList = "project_id,version_id,revision_id,page_id",
                    unique = true),
            @Index(name = "idx_copy_snapshot_version", columnList = "version_id")
        })
@Schema(description = "固定页面文案清单提取快照")
public class PrototypeCopySnapshotEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "所属逻辑版本")
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "不可变修订")
    private String revisionId;

    @Column(name = "page_id", length = 64, nullable = false)
    @Schema(description = "清单页面标识")
    private String pageId;

    @Column(name = "page_path", length = 1024, nullable = false)
    @Schema(description = "清单中的规范相对路径")
    private String pagePath;

    @Column(name = "manifest_sha256", length = 64, nullable = false)
    @Schema(description = "固定清单摘要")
    private String manifestSha256;

    @Column(name = "file_sha256", length = 64, nullable = false)
    @Schema(description = "清单声明的页面原文件摘要")
    private String fileSha256;

    @Column(name = "extractor_version", nullable = false)
    @Schema(description = "首次提取规则版本，当前为1")
    private Integer extractorVersion;

    @Column(name = "extraction_state", length = 32, nullable = false)
    @Schema(description = "READY、EMPTY或LIMIT_EXCEEDED")
    private String extractionState;

    @Column(name = "limit_reason", length = 32)
    @Schema(description = "预算失败原因；成功为空")
    private String limitReason;

    @Column(name = "static_count", nullable = false)
    @Schema(description = "完整去重静态条目数；预算失败为零")
    private Integer staticCount;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "整页清单并发版本；条目变更成功递增")
    private Long configVersion;

    @Column(name = "extracted_at", nullable = false)
    @Schema(description = "首次提取Unix毫秒")
    private Long extractedAt;
}
