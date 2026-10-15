package com.gaiys.proto.system.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Anonymous login-page contracts retained after removing the private platform runtime. */
@Tag(name = "登录页面")
@RestController
@Authorize(ignore = true)
public class LoginPageController {
    private final com.gaiys.proto.system.service.SystemConfigService front;

    public LoginPageController(com.gaiys.proto.system.service.SystemConfigService front) {
        this.front = front;
    }

    @Operation(summary = "读取登录页面公开配置")
    @GetMapping("/system/config/front")
    public Mono<java.util.Map<String, Object>> front() {
        return front.get("front");
    }

    @Operation(summary = "读取可用的单点登录应用")
    @GetMapping("/application/sso/_all")
    public Mono<List<Object>> ssoApplications() {
        // The standalone backend has no SSO integrations; the UI hides third-party login.
        return Mono.just(List.of());
    }
}
