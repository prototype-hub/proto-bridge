package com.gaiys.proto.system.web;

import com.gaiys.proto.system.dto.config.CaptchaConfig;
import com.gaiys.proto.system.dto.config.LoginConfig;
import com.gaiys.proto.system.security.properties.CaptchaProperties;
import com.gaiys.proto.system.service.CaptchaService;
import com.gaiys.proto.system.service.LoginEncryptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 登录页面辅助接口。公开验证码和加密的客户端配置，挑战生命周期和密钥材料由安全服务维护。 */
@Tag(name = "登录辅助")
@RestController
@RequestMapping("/authorize")
public class LoginSupportController {
    private final CaptchaProperties captcha;
    private final CaptchaService service;
    private final LoginEncryptionService encryption;

    public LoginSupportController(
            CaptchaProperties captcha, CaptchaService service, LoginEncryptionService encryption) {
        this.captcha = captcha;
        this.service = service;
        this.encryption = encryption;
    }

    @Operation(summary = "查询登录验证码配置")
    @GetMapping("/captcha/config")
    public Mono<CaptchaConfig> captchaConfig() {
        return Mono.just(new CaptchaConfig(captcha.isEnabled(), captcha.getTtl().toMillis()));
    }

    @Operation(summary = "生成一次性登录验证码")
    @GetMapping("/captcha/image")
    public Mono<CaptchaService.Image> image(
            @RequestParam(defaultValue = "130") int width,
            @RequestParam(defaultValue = "40") int height) {
        return service.image(width, height);
    }

    @Operation(summary = "查询登录加密配置")
    @GetMapping("/login/configs")
    public Mono<LoginConfig> loginConfig() {
        return encryption.config().map(LoginConfig::new);
    }
}
