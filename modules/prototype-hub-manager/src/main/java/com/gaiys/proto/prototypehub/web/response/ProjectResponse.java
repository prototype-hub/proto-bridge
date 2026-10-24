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
        @Schema(description = "最近修改时间（Unix 毫秒）") Long updatedAt,
        @Schema(description = "项目所有者用户标识，历史未认领项目为空") String ownerId,
        @Schema(description = "当前用户项目角色，owner/editor/viewer/admin") String role,
        @Schema(description = "是否缺少有效所有者，需要管理员认领") boolean claimRequired,
        @Schema(description = "项目范围能力，与菜单动作权限取交集") ProjectCapabilities capabilities,
        @Schema(description = "软删除时间（Unix 毫秒），活动项目为空") Long deletedTime) {
    public static ProjectResponse from(
            PrototypeProjectEntity p, String role, boolean claimRequired) {
        return new ProjectResponse(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getCover(),
                p.getCreatorId(),
                p.getCreateTime(),
                p.getModifyTime(),
                p.getOwnerId(),
                role,
                claimRequired,
                ProjectCapabilities.forRole(role),
                p.getDeletedTime());
    }
}
