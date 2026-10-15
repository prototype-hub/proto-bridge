package com.gaiys.proto.system.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.persistence.Column;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

/** 账号关联资料的持久化模型。主键与官方用户标识一致，账号和密码字段由 hsweb 用户模型维护。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "s_user_detail")
@Schema(description = "用户联系方式与头像信息，主键与用户标识一致")
public class UserDetailEntity extends GenericEntity<String> {
    @Column(length = 255)
    @Schema(description = "用户电子邮箱")
    private String email;

    @Column(length = 64)
    @Schema(description = "用户联系电话")
    private String telephone;

    @Column(length = 1024)
    @Schema(description = "用户头像地址")
    private String avatar;
}
