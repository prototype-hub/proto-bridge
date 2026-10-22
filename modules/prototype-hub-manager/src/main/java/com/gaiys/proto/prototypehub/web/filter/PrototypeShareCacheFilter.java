package com.gaiys.proto.prototypehub.web.filter;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 公开分享含快照能力凭据，成功和失败响应均禁止缓存，避免配置撤销后重用旧授权内容。 */
@Component
// 必须在 /api 前缀剥离之后、运行资源 CORS 短路之前设置，覆盖预检和来源拒绝响应。
@Order(-95)
public class PrototypeShareCacheFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith("/prototype-hub/public/shares/"))
            exchange.getResponse().getHeaders().setCacheControl("no-store");
        return chain.filter(exchange);
    }
}
