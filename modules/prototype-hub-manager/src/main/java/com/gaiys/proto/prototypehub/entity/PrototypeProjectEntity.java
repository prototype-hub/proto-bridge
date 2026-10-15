package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

@Getter
@Setter
@Table(name = "p_project")
@EnableEntityEvent
@Schema(description = "原型项目及创建修改信息")
public class PrototypeProjectEntity extends GenericEntity<String> {
    @Column(name = "name", length = 255, nullable = false)
    @Schema(description = "原型项目名称")
    private String name;

    @Column(name = "description", length = 2000)
    @Schema(description = "原型项目说明")
    private String description;

    @Column(name = "cover", length = 1024)
    @Schema(description = "原型项目封面地址")
    private String cover;

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
