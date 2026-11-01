package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.JDBCType;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 静态原文与人工补充共用数据库清单；所有筛选、计数及分页在同一固定范围内执行。 */
@Getter
@Setter
@Table(
        name = "p_copy_entry",
        indexes = {
            @Index(
                    name = "idx_copy_entry_page",
                    columnList = "snapshot_id,kind,source_order,created_at,id"),
            @Index(name = "idx_copy_entry_version", columnList = "version_id")
        })
@Schema(description = "固定页面文案条目")
public class PrototypeCopyEntryEntity extends GenericEntity<String> {
    @Column(name = "snapshot_id", length = 64, nullable = false)
    @Schema(description = "首次提取快照标识")
    private String snapshotId;

    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "所属逻辑版本")
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "固定修订")
    private String revisionId;

    @Column(name = "page_id", length = 64, nullable = false)
    @Schema(description = "固定页面")
    private String pageId;

    @Column(nullable = false)
    @Schema(description = "0为静态原文，1为人工补充")
    private Integer kind;

    @Column(name = "source_order", nullable = false)
    @Schema(description = "静态首次出现序号；人工为零")
    private Integer sourceOrder;

    @Column(nullable = false)
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "完整纯文本；静态原文不可修改，不静默截断")
    private String text;

    @Column(name = "business_term", length = 200, nullable = false)
    @Schema(description = "人工业务词；空串表示未维护")
    private String businessTerm;

    @Column(length = 2000, nullable = false)
    @Schema(description = "人工纯文本备注")
    private String note;

    @Column(name = "requirement_url", length = 2048, nullable = false)
    @Schema(description = "人工HTTP(S)需求链接；不抓取外部内容")
    private String requirementUrl;

    @Column(name = "occurrence_count", nullable = false)
    @Schema(description = "静态原文出现次数；人工为零")
    private Integer occurrenceCount;

    @Column(name = "occurrences_json", nullable = false)
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "完整出现序号与来源分类；不保存可执行选择器或HTML")
    private String occurrencesJson;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "条目CAS版本，从零递增")
    private Long configVersion;

    @Column(name = "created_at", nullable = false)
    @Schema(description = "创建Unix毫秒")
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    @Schema(description = "最近维护Unix毫秒")
    private Long updatedAt;
}
