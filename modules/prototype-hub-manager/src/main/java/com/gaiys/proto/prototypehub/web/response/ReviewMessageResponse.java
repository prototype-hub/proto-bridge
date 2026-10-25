package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 页面评审的显式投影视图，不公开作者ID、邮箱或凭据。 */
@Schema(description = "页面评审投影视图")
public record ReviewMessageResponse(
        @Schema(description = "消息标识") String id,
        @Schema(description = "所属线程") String threadId,
        @Schema(description = "纯文本正文") String text,
        @Schema(description = "guest或member") String authorType,
        @Schema(description = "作者显示名快照") String authorName,
        @Schema(description = "访客自述公司，成员为空") String authorCompany,
        @Schema(description = "访客身份等级，成员为空") String verificationLevel,
        @Schema(description = "public或internal") String visibility,
        @Schema(description = "创建Unix毫秒") long createdAt) {}
