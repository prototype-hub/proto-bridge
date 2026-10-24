package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeReviewService;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import com.gaiys.proto.prototypehub.web.request.ReviewChallengeRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewGrantRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewVerifyRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewChallengeResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewContextResponse;
import com.gaiys.proto.prototypehub.web.security.ReviewRequestSecurity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 公开分享邮箱身份的独立POST入口；仅服务可信外壳，iframe资源请求不使用此Cookie或CSRF。 */
@RestController
@RequiredArgsConstructor
@Authorize(ignore = true)
@Tag(name = "公开分享邮箱身份")
@RequestMapping(
        value = "/prototype-hub/public/shares/{token}/review",
        consumes = MediaType.APPLICATION_JSON_VALUE)
public class PublicPrototypeReviewController {
    private final PrototypeReviewService review;
    private final ReviewRequestSecurity security;

    @PostMapping("/context")
    @Operation(summary = "读取固定分享修订邮箱身份上下文")
    public Mono<ReviewContextResponse> context(
            @PathVariable String token,
            @Valid @RequestBody ReviewGrantRequest input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        String cookie = security.contextCookie(exchange);
        return review.context(token, input.revisionId(), input.accessKey(), cookie)
                .doOnNext(ignored -> security.cookie(exchange, token, cookie));
    }

    @PostMapping("/challenges")
    @Operation(summary = "发送一次邮箱验证码", description = "需要当前分享授权、可信Origin及Cookie绑定CSRF；失败也占用限频")
    public Mono<ReviewChallengeResponse> challenge(
            @PathVariable String token,
            @Valid @RequestBody ReviewChallengeRequest input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        return review.challenge(token, input, security.unsafeCookie(exchange));
    }

    @PostMapping("/verify")
    @Operation(summary = "一次性验证邮箱所有权并轮换短期身份Cookie")
    public Mono<ReviewContextResponse> verify(
            @PathVariable String token,
            @Valid @RequestBody ReviewVerifyRequest input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        return review.verify(token, input, security.unsafeCookie(exchange))
                .map(
                        result -> {
                            security.cookie(exchange, token, result.cookie());
                            return result.context();
                        });
    }

    @PostMapping("/logout")
    @Operation(summary = "结束当前邮箱身份并轮换为匿名Cookie")
    public Mono<Map<String, Boolean>> logout(
            @PathVariable String token,
            @Valid @RequestBody ReviewGrantRequest input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        return review.logout(
                        token,
                        input.revisionId(),
                        input.accessKey(),
                        security.unsafeCookie(exchange))
                .then(
                        Mono.fromSupplier(
                                () -> {
                                    security.cookie(exchange, token, PrototypeReviewKeys.random());
                                    return Map.of("loggedOut", true);
                                }));
    }
}
