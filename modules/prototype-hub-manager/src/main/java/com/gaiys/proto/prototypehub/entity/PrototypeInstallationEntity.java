package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 同一数据库的部署身份；数据库备份与恢复时保留该身份。 */
@Getter
@Setter
@Table(name = "p_installation")
@Schema(description = "原型平台部署身份")
public class PrototypeInstallationEntity extends GenericEntity<String> {
    @Column(name = "installation_id", length = 36, nullable = false)
    @Schema(description = "持久化部署标识，与进程、IP 和机器无关")
    private String installationId;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "部署身份创建时间（Unix 毫秒）")
    private Long createTime;
}
