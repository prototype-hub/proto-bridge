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

/** 固定修订页面的人工交付结论；不等同上传状态、正式批准或线程解决，不跨修订继承。 */
@Getter
@Setter
@Table(
        name = "p_page_delivery",
        indexes = {
            @Index(
                    name = "idx_page_delivery_state",
                    columnList = "revision_id,state,updated_at,page_id"),
            @Index(
                    name = "idx_page_delivery_revision",
                    columnList = "revision_id,updated_at,page_id"),
            @Index(name = "idx_page_delivery_version", columnList = "version_id")
        })
@Schema(description = "固定页面人工交付状态")
public class PrototypePageDeliveryEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "所属逻辑版本标识")
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "不可变修订标识，不随当前指针迁移")
    private String revisionId;

    @Column(name = "page_id", length = 64, nullable = false)
    @Schema(description = "发布清单中页面路径的SHA-256标识")
    private String pageId;

    @Column(name = "page_name", nullable = false)
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "服务端不可变清单的页面名称快照")
    private String pageName;

    @Column(name = "page_path", nullable = false)
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "服务端不可变清单的页面路径快照")
    private String pagePath;

    @Column(name = "state", length = 16, nullable = false)
    @Schema(description = "人工DRAFT/READY/IN_PROGRESS/DONE状态")
    private String state;

    @Column(name = "assignee_id", length = 64)
    @Schema(description = "人工负责人，分派不赋予权限")
    private String assigneeId;

    @Column(name = "assignee_name", length = 128)
    @Schema(description = "分派时姓名快照，失效后仍可辨认")
    private String assigneeName;

    @Column(name = "reason", length = 2000, nullable = false)
    @Schema(description = "可选纯文本理由，空值规范为空串")
    private String reason;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "首次保存为一，每个新成功请求递增的CAS版本")
    private Long configVersion;

    @Column(name = "updated_at", nullable = false)
    @Schema(description = "最近人工更新Unix毫秒")
    private Long updatedAt;
}
