package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 成员选择所需的最小公开用户信息。 */
@Schema(description = "可添加至项目的有效用户，仅返回必要公开字段")
public record MemberCandidateResponse(
        @Schema(description = "用户标识") String userId,
        @Schema(description = "用户姓名") String name,
        @Schema(description = "用户登录名") String username) {}
