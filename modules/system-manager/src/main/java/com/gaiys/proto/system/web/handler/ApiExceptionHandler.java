package com.gaiys.proto.system.web.handler;

import com.gaiys.proto.system.web.errors.ApiErrors;
import com.gaiys.proto.system.web.filter.AuditWebFilter;
import com.gaiys.proto.system.web.response.ApiResponse;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.DisconnectedClientHelper;
import reactor.core.Exceptions;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiResponse<Void>> handle(
            Throwable error,
            Locale locale,
            org.springframework.web.server.ServerWebExchange exchange)
            throws Throwable {
        // 资源已开始发送时不能再写 JSON；断开连接交给 Spring 按原异常识别处理。
        if (exchange.getResponse().isCommitted()
                || DisconnectedClientHelper.isClientDisconnectedException(error)) throw error;
        ApiResponse<Void> response = ApiErrors.response(error, locale);
        exchange.getAttributes().put(AuditWebFilter.ERROR_CODE, response.getCode());
        exchange.getResponse().getHeaders().clearContentHeaders();
        var builder = ResponseEntity.status(response.getStatus());
        // 保留 Spring 状态异常携带的协议头，例如 405 的 Allow。
        if (Exceptions.unwrap(error) instanceof ResponseStatusException http)
            builder.headers(http.getHeaders());
        return builder.contentType(MediaType.APPLICATION_JSON).body(response);
    }
}
