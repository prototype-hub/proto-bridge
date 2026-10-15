package com.gaiys.proto.system.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.sql.JDBCType;
import java.util.List;
import java.util.Map;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericTreeSortSupportEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

/** 职位持久化模型。职位归属组织创建后保持不变，关联名称和子职位列表仅用于查询结果。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "s_position",
        indexes = @Index(name = "proto_position_org_idx", columnList = "org_id"))
@Schema(description = "组织下的职位及关联角色配置")
public class PositionEntity extends GenericTreeSortSupportEntity<String> {
    @Column(length = 128, nullable = false)
    @NotBlank
    @Schema(description = "职位名称")
    private String name;

    @Column(length = 64, nullable = false, updatable = false)
    @NotBlank
    @Schema(description = "所属组织标识（创建后不可变）")
    private String orgId;

    @Column(length = 64)
    @Schema(description = "职位编码")
    private String code;

    @Column(length = 1024)
    @Schema(description = "职位说明")
    private String description;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "职位关联的角色标识与名称列表")
    private List<Map<String, String>> roles;

    @Schema(description = "所属组织名称（关联查询结果）")
    private String orgName;

    @Schema(description = "上级职位名称（关联查询结果）")
    private String parentName;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(description = "职位直接绑定的用户数量（关联查询结果）", accessMode = Schema.AccessMode.READ_ONLY)
    private long memberCount;

    @Schema(description = "子职位列表（树形返回结果）")
    private List<PositionEntity> children;
}
