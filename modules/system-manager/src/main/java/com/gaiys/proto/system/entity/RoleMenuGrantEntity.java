package com.gaiys.proto.system.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.JDBCType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.persistence.Column;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

/** 角色菜单选择的持久化记录。菜单授权服务维护选择与受管资源，实际账号和数据权限仍由 hsweb 解析。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "s_role_menu_grant")
@Schema(description = "角色菜单与按钮授权记录，主键为角色标识")
public class RoleMenuGrantEntity extends GenericEntity<String> {
    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "已选菜单与已授权按钮标识的映射")
    private Map<String, List<String>> menus;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "由菜单授权维护的权限资源标识集合")
    private Set<String> managedPermissions;
}
