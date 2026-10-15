package com.gaiys.proto.prototypehub.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 分享密码校验请求；公开接口决定访问授权，管理接口只返回是否匹配且不授予访问密钥。 */
@Schema(description = "原型分享密码校验请求")
public record ShareVerifyRequest(
        @Size(max = 128) @Schema(description = "分享访问密码") String password) {}
