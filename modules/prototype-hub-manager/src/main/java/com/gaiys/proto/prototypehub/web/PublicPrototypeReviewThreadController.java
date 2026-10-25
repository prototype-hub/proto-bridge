package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeReviewThreadService;
import com.gaiys.proto.prototypehub.web.request.ReviewMessageQueryRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewPageRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewTextRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewThreadQueryRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewThreadStateRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewMessageResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewThreadResponse;
import com.gaiys.proto.prototypehub.web.security.ReviewRequestSecurity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 公开线程读写均要求当前访客与Cookie绑定CSRF；原型iframe不接收此入口的任何数据。 */
@RestController
@RequiredArgsConstructor
@Authorize(ignore = true)
@Tag(name = "公开页面评审")
@RequestMapping("/prototype-hub/public/shares/{token}/review/threads")
public class PublicPrototypeReviewThreadController {
    private final PrototypeReviewThreadService threads;
    private final ReviewRequestSecurity security;

    @PostMapping("/_query")
    @Operation(summary = "读取当前访客本人页面线程")
    public Mono<PagerResult<ReviewThreadResponse>> query(
            @PathVariable String token,
            @Validated({Default.class, ReviewPageRequest.Public.class}) @RequestBody
                    ReviewThreadQueryRequest input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        String cookie = security.unsafeCookie(exchange);
        return threads.queryVisitor(token, input, cookie);
    }

    @PostMapping("")
    @Operation(summary = "创建本人页面纯文本线程")
    public Mono<ReviewThreadResponse> create(
            @PathVariable String token,
            @Validated({Default.class, ReviewPageRequest.Public.class}) @RequestBody
                    ReviewTextRequest input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        String cookie = security.unsafeCookie(exchange);
        return threads.create(token, input, cookie);
    }

    @PostMapping("/{threadId}/messages/_query")
    @Operation(summary = "分页读取本人线程的公开消息")
    public Mono<PagerResult<ReviewMessageResponse>> messages(
            @PathVariable String token,
            @PathVariable String threadId,
            @Validated({Default.class, ReviewPageRequest.Public.class}) @RequestBody
                    ReviewMessageQueryRequest input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        String cookie = security.unsafeCookie(exchange);
        return threads.messagesVisitor(token, threadId, input, cookie);
    }

    @PostMapping("/{threadId}/messages")
    @Operation(summary = "追加本人对客消息并在需要时重开")
    public Mono<ReviewMessageResponse> reply(
            @PathVariable String token,
            @PathVariable String threadId,
            @Validated({Default.class, ReviewPageRequest.Public.class}) @RequestBody
                    ReviewTextRequest input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        String cookie = security.unsafeCookie(exchange);
        return threads.replyVisitor(token, threadId, input, cookie);
    }

    @PutMapping("/{threadId}/status")
    @Operation(summary = "显式重开本人线程")
    public Mono<ReviewThreadResponse> state(
            @PathVariable String token,
            @PathVariable String threadId,
            @Validated({Default.class, ReviewPageRequest.Public.class}) @RequestBody
                    ReviewThreadStateRequest input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        String cookie = security.unsafeCookie(exchange);
        return threads.stateVisitor(token, threadId, input, cookie);
    }
}
