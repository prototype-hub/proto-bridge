package com.gaiys.proto.system.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.basic.web.ReactiveUserTokenController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 当前会话退出的 POST 入口；已发布的 GET 仍由官方控制器提供，不接收其他用户令牌。 */
@Tag(name = "当前会话")
@RestController
@RequiredArgsConstructor
public class TokenSessionController {
    private final ReactiveUserTokenController tokens;

    @Operation(
            summary = "退出当前会话",
            description = "建议使用 POST /user-token/reset；原 GET 地址保持兼容，均仅重置当前令牌")
    @PostMapping("/user-token/reset")
    @Authorize(merge = false)
    public Mono<Boolean> reset() {
        return tokens.resetToken();
    }
}
