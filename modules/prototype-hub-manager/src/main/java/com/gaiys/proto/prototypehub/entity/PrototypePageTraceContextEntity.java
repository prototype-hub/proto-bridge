package com.gaiys.proto.prototypehub.entity;

import com.gaiys.proto.prototypehub.web.info.PageTraceSourceRef;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.JDBCType;
import java.util.List;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 固定修订人工追踪元数据；不改变原型、来源生命周期或正式批准。 */
@Getter
@Setter
@Table(
        name = "p_page_trace_context",
        indexes = {
            @Index(
                    name = "idx_page_trace_ctx_page",
                    columnList = "revision_id,page_id",
                    unique = true),
            @Index(
                    name = "idx_page_trace_ctx_entry",
                    columnList = "revision_id,entry,updated_at,id"),
            @Index(name = "idx_page_trace_ctx_version", columnList = "version_id"),
            @Index(name = "idx_page_trace_ctx_project", columnList = "project_id")
        })
@Schema(description = "固定修订页面人工追踪元数据")
public class PrototypePageTraceContextEntity extends GenericEntity<String> {
    @Column(name = "project_id", nullable = false, length = 64)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", nullable = false, length = 64)
    @Schema(description = "所属逻辑版本标识")
    private String versionId;

    @Column(name = "revision_id", nullable = false, length = 64)
    @Schema(description = "不可变修订标识，不随当前修订迁移")
    private String revisionId;

    @Column(name = "page_id", nullable = false, length = 128)
    @Schema(description = "固定修订真实内容页标识")
    private String pageId;

    @Column(name = "entry", nullable = false)
    @Schema(description = "人工入口声明，不改变实际HTML入口")
    private Boolean entry;

    @Column(name = "source_refs", nullable = false)
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "至多四十个来源身份与成员确认版本，不复制内容")
    private List<PageTraceSourceRef> references;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "初始为零、递增的安全整数CAS版本")
    private Long configVersion;

    @Column(name = "created_at", nullable = false)
    @Schema(description = "创建Unix毫秒")
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    @Schema(description = "最近维护Unix毫秒")
    private Long updatedAt;
}
