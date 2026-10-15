package com.gaiys.proto.system.web.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/** 当前用户可修改的公开资料。账号、状态、权限和目标用户只能来自认证上下文。 */
@Getter
@Setter
@Schema(description = "当前用户的公开资料更新请求")
public class UserProfileRequest {
    @NotBlank
    @Size(max = 128)
    @Schema(description = "用户显示名称")
    private String name;

    @Size(max = 1024)
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    @Schema(description = "头像地址；省略保留原值，空值清除")
    private String avatar;

    @Size(max = 64)
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    @Schema(description = "联系电话；省略保留原值，空值清除")
    private String telephone;

    @Size(max = 255)
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    @Schema(description = "电子邮箱；省略保留原值，空值清除")
    private String email;

    /** 不接收身份和权限字段，避免全局忽略未知 JSON 属性掩盖越权请求。 */
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("validation.self_profile_invalid");
    }

    public Map<String, Object> details() {
        Map<String, Object> values = new LinkedHashMap<>();
        if (avatar != null) values.put("avatar", avatar);
        if (telephone != null) values.put("telephone", telephone);
        if (email != null) values.put("email", email);
        return values;
    }
}
