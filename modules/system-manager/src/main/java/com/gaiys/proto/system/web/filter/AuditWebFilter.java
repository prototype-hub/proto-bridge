package com.gaiys.proto.system.web.filter;

import com.gaiys.proto.logging.dispatcher.AuditDispatcher;
import com.gaiys.proto.logging.event.AuditEvent;
import com.gaiys.proto.system.web.errors.ApiErrors;
import com.gaiys.proto.system.web.response.ApiResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.hswebframework.web.authorization.Authentication;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(2)
public class AuditWebFilter implements WebFilter {
    public static final String ERROR_CODE = "proto.audit.error";
    private final AuditDispatcher dispatcher;

    public AuditWebFilter(AuditDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(
                () -> {
                    String supplied = exchange.getRequest().getHeaders().getFirst("X-Request-ID");
                    String trace =
                            supplied != null && supplied.matches("[A-Za-z0-9_-]{1,64}")
                                    ? supplied
                                    : UUID.randomUUID().toString();
                    exchange.getResponse().getHeaders().set("X-Request-ID", trace);
                    String path = exchange.getRequest().getPath().value();
                    String method = exchange.getRequest().getMethod().name();
                    Action action = classify(path, method);
                    long start = System.nanoTime();
                    AtomicReference<Throwable> failure = new AtomicReference<>();
                    return Authentication.currentReactive()
                            .map(auth -> auth.getUser().getId())
                            .onErrorReturn("anonymous")
                            .defaultIfEmpty("anonymous")
                            .flatMap(
                                    actor ->
                                            chain.filter(exchange)
                                                    .doOnError(failure::set)
                                                    .doFinally(
                                                            signal -> {
                                                                String code =
                                                                        exchange.getAttribute(
                                                                                ERROR_CODE);
                                                                if (action == null
                                                                        && !(code != null
                                                                                && code.startsWith(
                                                                                        "PROTOTYPE_HUB_ZIP")))
                                                                    return;
                                                                ApiResponse<Void> error =
                                                                        failure.get() == null
                                                                                ? null
                                                                                : ApiErrors
                                                                                        .response(
                                                                                                failure
                                                                                                        .get(),
                                                                                                java
                                                                                                        .util
                                                                                                        .Locale
                                                                                                        .ENGLISH);
                                                                int status =
                                                                        error != null
                                                                                ? error.getStatus()
                                                                                : exchange.getResponse()
                                                                                                        .getStatusCode()
                                                                                                == null
                                                                                        ? 200
                                                                                        : exchange.getResponse()
                                                                                                .getStatusCode()
                                                                                                .value();
                                                                if (error != null)
                                                                    code = error.getCode();
                                                                String result =
                                                                        signal
                                                                                        == reactor
                                                                                                .core
                                                                                                .publisher
                                                                                                .SignalType
                                                                                                .CANCEL
                                                                                ? "cancelled"
                                                                                : status < 400
                                                                                        ? "success"
                                                                                        : code
                                                                                                        != null
                                                                                                ? code
                                                                                                : "HTTP_"
                                                                                                        + status;
                                                                Action selected =
                                                                        code != null
                                                                                        && code
                                                                                                .startsWith(
                                                                                                        "PROTOTYPE_HUB_ZIP")
                                                                                ? new Action(
                                                                                        "zip.reject",
                                                                                        "version",
                                                                                        action
                                                                                                        == null
                                                                                                ? null
                                                                                                : action
                                                                                                        .id())
                                                                                : action;
                                                                dispatcher.offer(
                                                                        new AuditEvent(
                                                                                trace,
                                                                                actor,
                                                                                selected.name(),
                                                                                selected.type(),
                                                                                selected.id(),
                                                                                result,
                                                                                (System.nanoTime()
                                                                                                - start)
                                                                                        / 1_000_000,
                                                                                System
                                                                                        .currentTimeMillis()));
                                                            }));
                });
    }

    private static Action classify(String path, String method) {
        if (path.equals("/authorize/login") && method.equals("POST"))
            return new Action("login", "user", null);
        // 只读分页和管理侧密码确认由访问日志记录，不能泛化为资源创建审计。
        if (method.equals("POST")
                && (path.equals("/prototype-hub/projects/_query")
                        || path.equals("/prototype-hub/versions/_query")
                        || path.equals("/prototype-hub/projects/_deleted/_query")
                        || path.equals("/prototype-hub/versions/_deleted/_query")
                        || path.matches("/prototype-hub/shares/[^/]+/verify-password")))
            return null;
        if (!java.util.Set.of("POST", "PUT", "PATCH", "DELETE").contains(method)
                || !path.startsWith("/prototype-hub/")) return null;
        String[] parts = path.split("/");
        if (parts.length < 3) return null;
        // 明确授权和成员管理动作，避免将短期预览签发或历史迁移误记为项目/版本创建。
        if (parts.length == 5
                && parts[2].equals("versions")
                && parts[4].equals("preview-session")
                && method.equals("POST"))
            return new Action("preview.authorize", "version", parts[3]);
        if (parts.length == 4
                && parts[2].equals("projects")
                && parts[3].equals("_ownership-migration")
                && method.equals("POST"))
            return new Action("project.ownership.migrate", "project", null);
        if (parts.length == 5
                && parts[2].equals("projects")
                && parts[4].equals("claim")
                && method.equals("POST")) return new Action("project.claim", "project", parts[3]);
        if (parts.length == 5
                && parts[4].equals("_restore")
                && method.equals("POST")
                && (parts[2].equals("projects") || parts[2].equals("versions"))) {
            String resource = parts[2].equals("projects") ? "project" : "version";
            return new Action(resource + ".restore", resource, parts[3]);
        }
        if (parts.length == 6 && parts[2].equals("projects") && parts[4].equals("members"))
            return new Action(
                    "project.members." + (method.equals("DELETE") ? "delete" : "update"),
                    "project",
                    parts[3]);
        String type =
                switch (parts[2]) {
                    case "projects" -> "project";
                    case "versions" -> "version";
                    case "shares" -> "share";
                    default -> "unknown";
                };
        if (type.equals("unknown")) return null;
        String id = parts.length > 3 && parts[3].matches("[A-Za-z0-9_-]{1,128}") ? parts[3] : null;
        String verb =
                method.equals("DELETE") ? "delete" : method.equals("PUT") ? "update" : "create";
        if (parts.length > 4 && parts[4].equals("versions")) {
            type = "version";
            verb = "upload";
            id = null;
        }
        if (parts.length > 4 && parts[4].equals("shares")) {
            type = "share";
            id = null;
        }
        return new Action(type + "." + verb, type, id);
    }

    private record Action(String name, String type, String id) {}
}
