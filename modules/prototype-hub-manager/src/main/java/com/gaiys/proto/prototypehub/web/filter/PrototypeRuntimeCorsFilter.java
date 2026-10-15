package com.gaiys.proto.prototypehub.web.filter;

import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 允许隔离原型读取自身的运行资源，由 Spring CORS 处理器维护预检与响应头。
 *
 * <p>仅能力链接的运行资源接受不透明来源，不携带凭证；实际文件读取仍由控制器校验预览或分享密钥。 过滤器在 API 前缀归一化后、全局 CORS 前执行，其余路由继续使用平台配置。
 */
@Component
@Order(-90)
public class PrototypeRuntimeCorsFilter implements WebFilter {
    private final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    private final CorsWebFilter runtime = new CorsWebFilter(source);

    public PrototypeRuntimeCorsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("null"));
        config.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
        config.setAllowedHeaders(List.of("Range"));
        config.setAllowCredentials(false);
        config.setMaxAge(1800L);
        source.registerCorsConfiguration("/prototype-hub/runtime/**", config);
        source.registerCorsConfiguration("/prototype-hub/public/shares/*/*/runtime/**", config);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 不接管管理 API 的预检；全局 CORS 开关及允许来源继续由平台配置决定。
        return source.getCorsConfiguration(exchange) == null
                ? chain.filter(exchange)
                : runtime.filter(exchange, chain);
    }
}
