package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 项目成员的最小公开身份与项目内角色。 */
@Schema(description = "项目成员的必要公开身份信息及项目角色")
public record ProjectMemberResponse(
        @Schema(description = "用户标识") String userId,
        @Schema(description = "用户姓名") String name,
        @Schema(description = "用户登录名") String username,
        @Schema(description = "项目角色，owner/editor/viewer") String role) {}
