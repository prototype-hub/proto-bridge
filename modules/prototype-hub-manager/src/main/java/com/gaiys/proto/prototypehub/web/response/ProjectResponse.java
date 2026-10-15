package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/** 原型项目管理接口的响应视图，保留前端使用的创建和修改时间字段。 */
@Schema(description = "原型项目响应信息")
public record ProjectResponse(
        @Schema(description = "原型项目标识") String id,
        @Schema(description = "原型项目名称") String name,
        @Schema(description = "原型项目说明") String description,
        @Schema(description = "原型项目封面地址") String cover,
        @Schema(description = "项目创建者用户标识") String createdBy,
        @Schema(description = "项目创建时间（Unix 毫秒）") Long createdAt,
        @Schema(description = "最近修改时间（Unix 毫秒）") Long updatedAt) {
    public static ProjectResponse from(PrototypeProjectEntity p) {
        return new ProjectResponse(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getCover(),
                p.getCreatorId(),
                p.getCreateTime(),
                p.getModifyTime());
    }
}
