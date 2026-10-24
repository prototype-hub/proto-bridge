package com.gaiys.proto.prototypehub.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

@Getter
@Setter
@Table(
        name = "p_share",
        indexes = {
            @Index(name = "idx_protohub_share_project", columnList = "project_id"),
            @Index(name = "idx_protohub_share_version", columnList = "version_id"),
            @Index(name = "idx_protohub_share_token", columnList = "token", unique = true)
        })
@EnableEntityEvent
@Schema(description = "原型版本分享链接及访问控制信息")
public class PrototypeShareEntity extends GenericEntity<String> {
    @Column(name = "review_enabled")
    @Schema(description = "是否允许邮箱访客身份，旧数据为空时按关闭处理")
    private Boolean reviewEnabled;

    @Column(name = "revision_id", length = 64)
    @Schema(description = "固定模式绑定的不可变快照标识；跟随最新模式为空，旧固定分享为空时需显式迁移")
    private String revisionId;

    @Column(name = "revision_mode", length = 16)
    @Schema(description = "快照模式：fixed 固定快照，latest 跟随最新；旧数据为空时按 fixed 处理")
    private String revisionMode;

    @Column(name = "revoked")
    @Schema(description = "是否永久撤销；旧数据为空时按未撤销处理，撤销后不可恢复")
    private Boolean revoked;

    @Column(name = "config_version")
    @Schema(description = "分享配置并发版本；旧数据为空时按0处理，每次管理变更递增")
    private Long configVersion;

    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "分享所属的原型项目标识")
    private String projectId;

    @Column(name = "version_id", length = 64, nullable = false)
    @Schema(description = "分享对应的原型版本标识")
    private String versionId;

    @Column(name = "description", length = 2000)
    @Schema(description = "分享说明")
    private String description;

    @Column(name = "token", length = 128, nullable = false)
    @Schema(description = "公开分享链接标识")
    private String token;

    @JsonIgnore
    @Column(name = "access_key", length = 128, nullable = false)
    @Schema(description = "分享凭据派生密钥；管理变更时轮换，不在响应中公开")
    private String accessKey;

    @JsonIgnore
    @Column(name = "password_hash", length = 255)
    @Schema(description = "分享访问密码的 BCrypt 哈希，未设置密码时为空")
    private String passwordHash;

    @Column(name = "expires_at")
    @Schema(description = "分享过期时间（Unix 毫秒），为空时不限制有效期")
    private Long expiresAt;

    @Column(name = "enabled", nullable = false)
    @Schema(description = "分享是否启用")
    private Boolean enabled;

    @Column(name = "creator_id", length = 64, nullable = false)
    @Schema(description = "分享创建者用户标识")
    private String creatorId;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "分享创建时间（Unix 毫秒）")
    private Long createTime;
}
