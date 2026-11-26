package com.gaiys.proto.prototypehub.entity;

import com.gaiys.proto.prototypehub.web.info.ReviewAnchor;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.JDBCType;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 内部成员的固定修订页面人工说明；几何快照不代表DOM身份或访问权限。 */
@Getter
@Setter
@Table(
        name = "p_element_description",
        indexes = {
            @Index(
                    name = "idx_element_description_page",
                    columnList = "revision_id,page_id,created_at,id"),
            @Index(name = "idx_element_description_version", columnList = "version_id")
        })
@Schema(description = "固定页面区域人工功能说明")
public class PrototypeElementDescriptionEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "所属逻辑版本标识")
    private String versionId;

    @Column(name = "revision_id", length = 64, nullable = false)
    @Schema(description = "固定修订，不随current迁移标识")
    private String revisionId;

    @Column(name = "page_id", length = 64, nullable = false)
    @Schema(description = "清单中的叶子页面标识")
    private String pageId;

    @Column(name = "title", length = 120, nullable = false)
    @Schema(description = "对象名称，普通文本")
    private String title;

    @Column(name = "purpose", length = 2000, nullable = false)
    @Schema(description = "对象用途，普通文本")
    private String purpose;

    @Column(name = "input_rules", length = 2000, nullable = false)
    @Schema(description = "输入规则，普通文本")
    private String inputRules;

    @Column(name = "permission_rules", length = 2000, nullable = false)
    @Schema(description = "权限规则，普通文本")
    private String permissionRules;

    @Column(name = "navigation_rules", length = 2000, nullable = false)
    @Schema(description = "跳转规则，普通文本")
    private String navigationRules;

    @Column(name = "notes", length = 4000, nullable = false)
    @Schema(description = "业务备注，普通文本")
    private String notes;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "可选根文档几何快照，不自动重锚")
    private ReviewAnchor anchor;

    @Column(name = "config_version", nullable = false)
    @Schema(description = "初始零、更新递增的CAS版本")
    private Long configVersion;

    @Column(name = "created_at", nullable = false)
    @Schema(description = "创建Unix毫秒")
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    @Schema(description = "更新Unix毫秒")
    private Long updatedAt;
}
