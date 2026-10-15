package com.gaiys.proto.system.web.filter;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Keeps generated /api capability links usable with or without an external gateway. */
@Component
@Order(-100)
public class ApiPrefixFilter implements WebFilter {
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        return chain.filter(
                path.startsWith("/api/")
                        ? exchange.mutate()
                                .request(
                                        exchange.getRequest()
                                                .mutate()
                                                .path(path.substring(4))
                                                .build())
                                .build()
                        : exchange);
    }
}
