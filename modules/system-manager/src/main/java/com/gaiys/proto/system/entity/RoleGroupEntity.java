package com.gaiys.proto.system.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import javax.persistence.Column;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericTreeSortSupportEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

/** 角色分组持久化模型。复用 hsweb 树形结构，子分组列表仅用于查询结果，角色本身仍由官方维度模型维护。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "s_role_group")
@Schema(description = "角色分组与分组树结构")
public class RoleGroupEntity extends GenericTreeSortSupportEntity<String> {
    @Column(length = 128, nullable = false)
    @NotBlank
    @Schema(description = "角色分组名称")
    private String name;

    @Schema(description = "子角色分组列表（树形返回结果）")
    private List<RoleGroupEntity> children;
}
