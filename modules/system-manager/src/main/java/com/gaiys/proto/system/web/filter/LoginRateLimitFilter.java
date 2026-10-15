package com.gaiys.proto.system.web.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 登录辅助与分享密码校验的频率限制过滤器。沿用固定时间窗口和客户端标识策略，两类分享校验共享配额。 */
@Component
@Order(3)
public class LoginRateLimitFilter implements WebFilter {
    private final Cache<String, Window> windows =
            Caffeine.newBuilder()
                    .maximumSize(10000)
                    .expireAfterAccess(Duration.ofMinutes(2))
                    .build();
    private final int limit;

    public LoginRateLimitFilter(
            @Value("${proto.security.authentication-requests-per-minute:100}") int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid rate limit");
        this.limit = limit;
    }

    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean loginPath =
                path.equals("/authorize/login")
                        || path.startsWith("/authorize/captcha/")
                        || path.equals("/authorize/login/configs");
        boolean guarded =
                loginPath
                        || (exchange.getRequest().getMethod()
                                        == org.springframework.http.HttpMethod.POST
                                && (path.matches("/prototype-hub/public/shares/[^/]+/verify")
                                        || path.matches(
                                                "/prototype-hub/shares/[^/]+/verify-password")));
        if (!guarded) return chain.filter(exchange);
        String peer =
                exchange.getRequest().getRemoteAddress() == null
                        ? "unknown"
                        : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        String bucket = peer + ":" + (loginPath ? "login" : "share");
        long now = System.currentTimeMillis();
        if (windows.estimatedSize() >= 10000 && !windows.asMap().containsKey(bucket))
            return denied(exchange);
        if (!windows.get(bucket, key -> new Window(now)).take(now, limit)) return denied(exchange);
        return chain.filter(exchange);
    }

    private Mono<Void> denied(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set("Retry-After", "60");
        return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));
    }

    public void clearExpired() {
        windows.cleanUp();
    }

    private static final class Window {
        volatile long start;
        int requests;

        Window(long start) {
            this.start = start;
        }

        synchronized boolean take(long now, int limit) {
            if (now - start >= 60000) {
                start = now;
                requests = 0;
            }
            return ++requests <= limit;
        }
    }
}
