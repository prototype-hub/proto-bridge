package com.gaiys.proto.system.web.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.system.web.errors.ApiErrors;
import com.gaiys.proto.system.web.filter.AuditWebFilter;
import com.gaiys.proto.system.web.response.ApiResponse;
import java.util.Locale;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class ApiErrorWebHandler implements ErrorWebExceptionHandler {
    private final ObjectMapper mapper;

    public ApiErrorWebHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) return Mono.error(error);
        Locale locale = exchange.getLocaleContext().getLocale();
        ApiResponse<Void> response =
                ApiErrors.response(error, locale == null ? Locale.ENGLISH : locale);
        exchange.getAttributes().put(AuditWebFilter.ERROR_CODE, response.getCode());
        exchange.getResponse()
                .setStatusCode(
                        org.springframework.http.HttpStatusCode.valueOf(response.getStatus()));
        // 路由阶段异常不会进入控制器 Advice，同样保留 Allow、Retry-After 等官方协议头。
        if (Exceptions.unwrap(error) instanceof ResponseStatusException http)
            exchange.getResponse().getHeaders().addAll(http.getHeaders());
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = mapper.writeValueAsBytes(response);
            return exchange.getResponse()
                    .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        } catch (JsonProcessingException serialization) {
            return Mono.error(serialization);
        }
    }
}
