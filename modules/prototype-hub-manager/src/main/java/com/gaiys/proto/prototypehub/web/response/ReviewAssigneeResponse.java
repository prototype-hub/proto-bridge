package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 内部候选最小投影，不返回登录名或邮箱。 */
@Schema(description = "线程分派候选")
public record ReviewAssigneeResponse(
        @Schema(description = "用户标识") String userId, @Schema(description = "当前姓名") String name) {}
