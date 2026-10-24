package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 分享上下文中的邮箱身份；CSRF 只供外壳请求，不向原型 iframe 传递。 */
@Schema(description = "固定分享修订的邮箱身份上下文")
public record ReviewContextResponse(
        @Schema(description = "固定修订标识") String revisionId,
        @Schema(description = "该分享是否启用邮箱身份") boolean enabled,
        @Schema(description = "服务器是否配置验证邮件传输") boolean mailAvailable,
        @Schema(description = "绑定当前HttpOnly Cookie的CSRF凭据") String csrfToken,
        @Schema(description = "已验证访客身份，匿名时为空") ReviewIdentity session) {
    @Schema(description = "邮箱所有权已验证的访客，自述名称和公司未经平台背书")
    public record ReviewIdentity(
            @Schema(description = "项目内稳定访客标识") String guestId,
            @Schema(description = "自述显示名称") String displayName,
            @Schema(description = "自述公司") String company,
            @Schema(description = "遮蔽后的邮箱") String maskedEmail,
            @Schema(description = "固定为email，只表示邮箱所有权") String verificationLevel,
            @Schema(description = "会话到期Unix毫秒") long expiresAt) {}
}
