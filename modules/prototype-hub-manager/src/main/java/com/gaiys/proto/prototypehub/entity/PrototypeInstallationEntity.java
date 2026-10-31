package com.gaiys.proto.prototypehub.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    @JsonIgnore
    @Column(name = "statistics_signing_key", length = 64)
    @Schema(description = "服务端统计票据签名密钥；仅初始化一次，随数据库备份，禁止对外返回")
    private String statisticsSigningKey;

    @Column(name = "statistics_started_at")
    @Schema(description = "首次启用分享访问采集的Unix毫秒；升级前历史不推测为零")
    private Long statisticsStartedAt;

    @JsonIgnore
    @Column(name = "license_document")
    @org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType(
            jdbcType = java.sql.JDBCType.LONGVARCHAR,
            javaType = String.class)
    @Schema(description = "规范化已接收凭证封套；只在服务端重新验签，不对外返回")
    private String licenseDocument;

    @JsonIgnore
    @Column(name = "license_id", length = 64)
    @Schema(description = "已接收授权谱系；不因信任配置变更重置")
    private String licenseId;

    @JsonIgnore
    @Column(name = "license_sequence")
    @Schema(description = "已接收单调序列，拒绝旧凭证覆盖")
    private Long licenseSequence;

    @JsonIgnore
    @Column(name = "license_fingerprint", length = 64)
    @Schema(description = "已接收规范封套SHA256，用于幂等及结果核对")
    private String licenseFingerprint;

    @JsonIgnore
    @Column(name = "license_accepted_at")
    @Schema(description = "首次接收当前凭证Unix毫秒，重复导入不移动")
    private Long licenseAcceptedAt;

    @JsonIgnore
    @Column(name = "license_key_id", length = 64)
    @Schema(description = "当前凭证签名公钥标识")
    private String licenseKeyId;

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
