package com.gaiys.proto.prototypehub.web.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 公开分享信息视图；访问授权通过后才填充运行文件访问密钥和预览地址。 */
@Schema(description = "公开原型分享信息")
public record PublicShareResponse(
        @Schema(description = "公开分享链接标识") String token,
        @Schema(description = "原型项目名称") String projectName,
        @Schema(description = "原型版本名称") String versionName,
        @Schema(description = "是否需要分享访问密码") boolean passwordRequired,
        @Schema(description = "分享过期时间（Unix 毫秒），为空时不限制有效期") Long expiresAt,
        @Schema(description = "授权通过后返回的运行文件访问密钥") String accessKey,
        @Schema(description = "授权通过后返回的运行文件基础地址") String runtimeBaseUrl,
        @Schema(description = "授权通过后返回的原型预览地址") String previewUrl) {}
