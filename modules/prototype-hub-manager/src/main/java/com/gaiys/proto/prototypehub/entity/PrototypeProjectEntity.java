package com.gaiys.proto.prototypehub.entity;

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
        name = "p_project",
        indexes = @Index(name = "idx_project_deleted", columnList = "deleted_time,id"))
@EnableEntityEvent
@Schema(description = "原型项目及创建修改信息")
public class PrototypeProjectEntity extends GenericEntity<String> {
    @Column(name = "deleted_time")
    @Schema(description = "软删除时间（Unix 毫秒），为空表示活动项目；30天后永久清理")
    private Long deletedTime;

    @Column(name = "name", length = 255, nullable = false)
    @Schema(description = "原型项目名称")
    private String name;

    @Column(name = "description", length = 2000)
    @Schema(description = "原型项目说明")
    private String description;

    @Column(name = "cover", length = 1024)
    @Schema(description = "原型项目封面地址")
    private String cover;

    @Column(name = "owner_id", length = 64)
    @Schema(description = "项目所有者用户标识，历史未认领项目为空")
    private String ownerId;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "access_epoch", length = 64)
    @Schema(description = "项目成员授权代次，成员变更后使既有内部预览凭证失效")
    private String accessEpoch;

    @Column(name = "creator_id", length = 64, nullable = false)
    @Schema(description = "项目创建者用户标识")
    private String creatorId;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "项目创建时间（Unix 毫秒）")
    private Long createTime;

    @Column(name = "modifier_id", length = 64, nullable = false)
    @Schema(description = "最近修改者用户标识")
    private String modifierId;

    @Column(name = "modify_time", nullable = false)
    @Schema(description = "最近修改时间（Unix 毫秒）")
    private Long modifyTime;
}
