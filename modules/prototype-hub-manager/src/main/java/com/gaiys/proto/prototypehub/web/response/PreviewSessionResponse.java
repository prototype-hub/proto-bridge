package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 与当前项目授权代次及文件内容绑定的短期原型预览地址。 */
@Schema(description = "仅用于隔离原型资源读取的短期授权，不包含平台登录令牌")
public record PreviewSessionResponse(
        @Schema(description = "短期运行文件基础地址") String runtimeBaseUrl,
        @Schema(description = "短期入口页面地址") String previewUrl,
        @Schema(description = "短期授权到期时间（Unix 毫秒）") long expiresAt) {}
