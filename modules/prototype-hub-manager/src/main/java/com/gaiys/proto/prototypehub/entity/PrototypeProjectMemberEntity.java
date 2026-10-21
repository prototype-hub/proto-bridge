package com.gaiys.proto.prototypehub.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

/** 项目成员绑定；所有者只由项目 ownerId 表达，避免出现两个所有权来源。 */
@Getter
@Setter
@Table(
        name = "p_project_member",
        indexes =
                @Index(
                        name = "idx_project_member_binding",
                        columnList = "project_id,user_id",
                        unique = true))
@EnableEntityEvent
@Schema(description = "原型项目成员及项目内角色")
public class PrototypeProjectMemberEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "成员所属原型项目标识")
    private String projectId;

    @Column(name = "user_id", length = 64, nullable = false)
    @Schema(description = "成员用户标识")
    private String userId;

    @Column(name = "role", length = 32, nullable = false)
    @Schema(description = "项目角色，editor 编辑者或 viewer 查看者")
    private String role;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "最近授权时间（Unix 毫秒）")
    private Long createTime;
}
