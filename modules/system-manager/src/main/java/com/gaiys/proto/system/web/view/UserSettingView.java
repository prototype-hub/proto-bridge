package com.gaiys.proto.system.web.view;

import com.gaiys.proto.system.entity.UserSettingEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/** 个人设置的接口视图。id 与用户范围内的 key 相同，供前端后续删除请求使用。 */
@Schema(description = "当前用户的个人设置")
public record UserSettingView(
        @Schema(description = "设置标识，与当前用户范围内的设置键相同") String id,
        @Schema(description = "设置键，在同一用户和类型内唯一") String key,
        @Schema(description = "个人配置类型或页面范围") String type,
        @Schema(description = "设置显示名称") String name,
        @Schema(description = "设置内容，保留原始 JSON 值类型") Object content,
        @Schema(description = "创建时间，Unix 毫秒") Long createTime) {

    public static UserSettingView of(UserSettingEntity entity) {
        return new UserSettingView(
                entity.getKey(),
                entity.getKey(),
                entity.getType(),
                entity.getName(),
                entity.getContent(),
                entity.getCreateTime());
    }
}
