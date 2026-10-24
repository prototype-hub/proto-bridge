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
        @Schema(description = "授权通过后返回、仅限本次快照的派生访问凭据") String accessKey,
        @Schema(description = "授权通过后返回的运行文件基础地址") String runtimeBaseUrl,
        @Schema(description = "授权通过后返回的原型预览地址") String previewUrl,
        @Schema(description = "本次授权绑定的不可变快照标识") String revisionId,
        @Schema(description = "快照模式：fixed 或 latest") String revisionMode,
        @Schema(description = "授权通过后返回的快照页面目录地址") String pagesUrl,
        @Schema(description = "该分享是否已显式开启邮箱身份") boolean reviewEnabled,
        @Schema(description = "邮箱身份是否已开启且验证邮件可用") boolean reviewAvailable) {}
