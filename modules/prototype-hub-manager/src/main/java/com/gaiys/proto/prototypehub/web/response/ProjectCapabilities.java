package com.gaiys.proto.prototypehub.web.response;

import com.gaiys.proto.prototypehub.enums.ProjectRole;
import io.swagger.v3.oas.annotations.media.Schema;

/** 仅描述项目范围能力；客户端仍需与通用菜单动作权限取交集。 */
@Schema(description = "当前用户在项目范围内的能力")
public record ProjectCapabilities(
        @Schema(description = "允许读取项目和版本") boolean read,
        @Schema(description = "允许修改项目信息和版本") boolean edit,
        @Schema(description = "允许管理成员") boolean manageMembers,
        @Schema(description = "允许删除空项目") boolean deleteProject,
        @Schema(description = "允许管理公开分享") boolean manageShares) {
    public static ProjectCapabilities forRole(String role) {
        return ProjectRole.of(role)
                .map(ProjectRole::capabilities)
                .orElseGet(() -> new ProjectCapabilities(false, false, false, false, false));
    }
}
