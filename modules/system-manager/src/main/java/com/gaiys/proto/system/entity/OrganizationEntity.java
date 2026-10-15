package com.gaiys.proto.system.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.sql.JDBCType;
import java.util.List;
import java.util.Map;
import javax.persistence.Column;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericTreeSortSupportEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

/** 组织持久化模型。继承 hsweb 树形排序能力，扩展属性存储于数据库，子组织列表仅用于查询结果。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "s_organization")
@Schema(description = "组织信息与组织树结构")
public class OrganizationEntity extends GenericTreeSortSupportEntity<String> {
    @Column(length = 128, nullable = false)
    @NotBlank
    @Schema(description = "组织名称")
    private String name;

    @Column(length = 64)
    @Schema(description = "组织编码")
    private String code;

    @Column(length = 32)
    @Schema(description = "组织类型")
    private String type;

    @Column(length = 1024)
    @Schema(description = "组织说明")
    private String describe;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "组织扩展属性")
    private Map<String, Object> properties;

    @Schema(description = "子组织列表（树形返回结果）")
    private List<OrganizationEntity> children;
}
