package com.gaiys.proto.system.web.filter;

import com.gaiys.proto.logging.document.LogDocument;
import com.gaiys.proto.logging.metadata.LogRequestMetadata;
import com.gaiys.proto.logging.properties.LogStorageProperties;
import com.gaiys.proto.logging.store.LogStore;
import com.gaiys.proto.system.web.errors.ApiErrors;
import java.util.UUID;
import org.hswebframework.web.authorization.Authentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Records matched routes and safe request metadata without consuming request bodies. */
@Component
@Order(3)
public class AccessLogWebFilter implements WebFilter {
    private final LogStore store;
    private final com.gaiys.proto.logging.writer.LocalAccessLogWriter localWriter;
    private final LogStorageProperties settings;
    private final String server;

    public AccessLogWebFilter(
            LogStore store,
            com.gaiys.proto.logging.writer.LocalAccessLogWriter localWriter,
            LogStorageProperties settings,
            @Value("${spring.application.name}:${server.port}") String server) {
        this.store = store;
        this.localWriter = localWriter;
        this.settings = settings;
        this.server = server;
    }

    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!settings.isAccessEnabled()
                || exchange.getRequest().getPath().value().startsWith("/actuator/"))
            return chain.filter(exchange);
        long started = System.currentTimeMillis();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        String trace = exchange.getResponse().getHeaders().getFirst("X-Request-ID");
        String requestId = trace == null ? UUID.randomUUID().toString() : trace;
        var headers = LogRequestMetadata.headers(exchange.getRequest().getHeaders());
        var parameters = LogRequestMetadata.parameters(exchange.getRequest().getQueryParams());
        var remote = exchange.getRequest().getRemoteAddress();
        // The peer address is trusted; forwarded headers need a separate trusted-proxy policy.
        String ip =
                remote == null || remote.getAddress() == null
                        ? null
                        : remote.getAddress().getHostAddress();
        String httpMethod = exchange.getRequest().getMethod().name();
        return Authentication.currentReactive()
                .map(
                        a ->
                                new Actor(
                                        a.getUser().getId(),
                                        a.getUser().getName(),
                                        a.getUser().getUsername()))
                .onErrorReturn(Actor.ANONYMOUS)
                .defaultIfEmpty(Actor.ANONYMOUS)
                .flatMap(
                        actor ->
                                chain.filter(exchange)
                                        .doOnError(failure::set)
                                        .doFinally(
                                                signal -> {
                                                    var pattern =
                                                            exchange.getAttribute(
                                                                    HandlerMapping
                                                                            .BEST_MATCHING_PATTERN_ATTRIBUTE);
                                                    String path =
                                                            pattern == null
                                                                    ? "[unmatched]"
                                                                    : pattern.toString();
                                                    int status =
                                                            exchange.getResponse().getStatusCode()
                                                                            == null
                                                                    ? 200
                                                                    : exchange.getResponse()
                                                                            .getStatusCode()
                                                                            .value();
                                                    var error =
                                                            failure.get() == null
                                                                    ? null
                                                                    : ApiErrors.response(
                                                                            failure.get(),
                                                                            java.util.Locale
                                                                                    .ENGLISH);
                                                    if (error != null) status = error.getStatus();
                                                    long completed = System.currentTimeMillis();
                                                    Object matched =
                                                            exchange.getAttribute(
                                                                    HandlerMapping
                                                                            .BEST_MATCHING_HANDLER_ATTRIBUTE);
                                                    HandlerMethod handler =
                                                            matched instanceof HandlerMethod value
                                                                    ? value
                                                                    : null;
                                                    var document =
                                                            LogDocument.builder()
                                                                    .kind("access")
                                                                    .server(server)
                                                                    .traceId(requestId)
                                                                    .actorId(actor.id())
                                                                    .username(actor.username())
                                                                    .userName(actor.name())
                                                                    .ip(ip)
                                                                    .target(
                                                                            handler == null
                                                                                    ? null
                                                                                    : handler.getBeanType()
                                                                                            .getName())
                                                                    .method(
                                                                            handler == null
                                                                                    ? null
                                                                                    : handler.getMethod()
                                                                                            .getName())
                                                                    .httpHeaders(headers)
                                                                    .parameters(parameters)
                                                                    .action(httpMethod + " " + path)
                                                                    .requestTime(started)
                                                                    .responseTime(completed)
                                                                    .createTime(started)
                                                                    .duration(completed - started)
                                                                    .url(path)
                                                                    .httpMethod(httpMethod)
                                                                    .status(status)
                                                                    .level(
                                                                            status >= 500
                                                                                    ? "ERROR"
                                                                                    : status >= 400
                                                                                            ? "WARN"
                                                                                            : "INFO")
                                                                    .exception(
                                                                            error == null
                                                                                    ? exchange
                                                                                            .getAttribute(
                                                                                                    AuditWebFilter
                                                                                                            .ERROR_CODE)
                                                                                    : error
                                                                                            .getCode())
                                                                    .build();
                                                    store.offer(document);
                                                    localWriter.offer(document);
                                                }));
    }

    private record Actor(String id, String name, String username) {
        private static final Actor ANONYMOUS = new Actor("anonymous", null, null);
    }
}
