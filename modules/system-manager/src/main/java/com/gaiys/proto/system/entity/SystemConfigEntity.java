package com.gaiys.proto.system.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.JDBCType;
import java.util.Map;
import javax.persistence.Column;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

/** 按配置域存储系统配置的持久化模型。配置域合法性与公开字段边界由系统配置服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "s_system_config")
@Schema(description = "系统配置分组，主键为 front、amap 或 paths 配置域")
public class SystemConfigEntity extends GenericEntity<String> {
    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @Schema(description = "当前配置域的配置项与取值")
    private Map<String, Object> properties;
}
