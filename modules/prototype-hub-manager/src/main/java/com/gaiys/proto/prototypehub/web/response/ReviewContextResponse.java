package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 分享上下文中的访客身份及保障等级；CSRF只供外壳请求，不向原型iframe传递。 */
@Schema(description = "固定分享修订的访客身份上下文")
public record ReviewContextResponse(
        @Schema(description = "固定修订标识") String revisionId,
        @Schema(description = "该分享是否启用访客身份") boolean enabled,
        @Schema(description = "是否明确允许未验证访客") boolean allowUnverified,
        @Schema(description = "当前分享是否允许页面文本评审") boolean allowComment,
        @Schema(description = "服务器是否配置验证邮件传输") boolean mailAvailable,
        @Schema(description = "绑定当前HttpOnly Cookie的CSRF凭据") String csrfToken,
        @Schema(description = "当前访客身份及保障等级，匿名时为空") ReviewIdentity session) {
    @Schema(description = "访客身份，自述名称和公司在任何等级下都未经平台背书")
    public record ReviewIdentity(
            @Schema(description = "项目访客标识；未验证身份随机且不可找回") String guestId,
            @Schema(description = "自述显示名称") String displayName,
            @Schema(description = "自述公司") String company,
            @Schema(description = "遮蔽后的邮箱；未验证身份为空") String maskedEmail,
            @Schema(description = "email仅表示邮箱所有权，unverified表示全部资料未经核验") String verificationLevel,
            @Schema(description = "会话到期Unix毫秒") long expiresAt) {}
}
