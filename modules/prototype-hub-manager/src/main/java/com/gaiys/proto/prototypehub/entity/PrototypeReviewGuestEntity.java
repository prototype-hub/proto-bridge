package com.gaiys.proto.prototypehub.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

/** 邮箱验证的项目访客身份，不属于平台内部用户。 */
@Getter
@Setter
@Table(
        name = "p_review_guest",
        indexes = {@Index(name = "idx_p_review_guest_project", columnList = "project_id")})
@Schema(description = "邮箱验证的项目访客身份，不属于平台内部用户")
public class PrototypeReviewGuestEntity extends GenericEntity<String> {
    @Column(name = "project_id", length = 64, nullable = false)
    @Schema(description = "所属项目标识")
    private String projectId;

    @JsonIgnore
    @Column(name = "email", length = 254, nullable = false)
    @Schema(description = "已验证规范邮箱，仅服务端使用")
    private String email;

    @JsonIgnore
    @Column(name = "display_name", length = 100, nullable = false)
    @Schema(description = "访客自述显示名称")
    private String displayName;

    @JsonIgnore
    @Column(name = "company", length = 200)
    @Schema(description = "访客自述公司名称")
    private String company;

    @Column(name = "create_time", nullable = false)
    @Schema(description = "首次验证时间（Unix毫秒）")
    private Long createTime;

    @Column(name = "modify_time", nullable = false)
    @Schema(description = "最近验证更新时间（Unix毫秒）")
    private Long modifyTime;
}
