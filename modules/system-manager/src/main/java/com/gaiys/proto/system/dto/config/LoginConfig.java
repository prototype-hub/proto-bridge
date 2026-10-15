package com.gaiys.proto.system.dto.config;

import com.gaiys.proto.system.service.LoginEncryptionService;
import io.swagger.v3.oas.annotations.media.Schema;

/** 登录页面加密配置视图。只包含公开密钥材料，不包含服务端私钥。 */
@Schema(description = "登录页面加密配置")
public record LoginConfig(
        @Schema(description = "登录请求加密配置与公开密钥") LoginEncryptionService.Config encrypt) {}
