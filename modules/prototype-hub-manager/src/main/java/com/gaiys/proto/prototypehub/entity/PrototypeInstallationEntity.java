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

    @Column(name = "retention_deleted_time")
    @Schema(description = "到期版本扫描游标的删除时间；仅作跨轮清理进度，不改变30天截止规则")
    private Long retentionDeletedTime;

    @Column(name = "retention_version_id", length = 64)
    @Schema(description = "到期版本扫描游标标识；与删除时间一起持久化，尾页重置以重试旧失败项")
    private String retentionVersionId;

    @Column(name = "retention_project_time")
    @Schema(description = "到期项目扫描游标的删除时间；独立于版本游标，避免失败父项目阻塞后续清理")
    private Long retentionProjectTime;

    @Column(name = "retention_project_id", length = 64)
    @Schema(description = "到期项目扫描游标标识；尾页重置后重新尝试之前失败的项目")
    private String retentionProjectId;
}
