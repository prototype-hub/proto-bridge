package com.gaiys.proto.prototypehub.web.security;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeReviewProperties;
import com.gaiys.proto.prototypehub.service.PrototypeReviewService;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/** 邮箱身份使用独立的精确Origin、HttpOnly路径Cookie及CSRF，不扩大平台CORS或登录令牌边界。 */
@Component
public class ReviewRequestSecurity {
    public static final String COOKIE = "prototype-review";
    private final PrototypeReviewProperties properties;
    private final String trustedOrigin;

    public ReviewRequestSecurity(PrototypeReviewProperties properties) {
        this.properties = properties;
        String configured = properties.getTrustedOrigin();
        if (configured == null || configured.isBlank()) this.trustedOrigin = null;
        else {
            // 配置允许origin末尾的根路径；浏览器Origin头仍不接受任何路径。
            try {
                this.trustedOrigin =
                        normalized(
                                configured.endsWith("/")
                                        ? configured.substring(0, configured.length() - 1)
                                        : configured);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid prototype-hub.review.trusted-origin");
            }
        }
    }

    /** 缺省只信任请求自身Origin；反代/开发代理必须显式配置单一可信Origin，不信任新转发头。 */
    public void origin(ServerWebExchange exchange) {
        List<String> values = exchange.getRequest().getHeaders().get("Origin");
        if (values == null || values.size() != 1) throw PrototypeReviewService.denied();
        String received = normalized(values.getFirst());
        URI target = exchange.getRequest().getURI();
        String expected =
                trustedOrigin == null
                        ? normalized(target.getScheme() + "://" + target.getRawAuthority())
                        : trustedOrigin;
        if (!PrototypeReviewKeys.matches(expected, received)
                || "cross-site"
                        .equalsIgnoreCase(
                                exchange.getRequest().getHeaders().getFirst("Sec-Fetch-Site")))
            throw PrototypeReviewService.denied();
        exchange.getResponse().getHeaders().setCacheControl("no-store");
    }

    private static String normalized(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getPort() == 0
                    || uri.getPort() > 65535) throw PrototypeReviewService.denied();
            int port =
                    uri.getPort() < 0
                            ? (scheme.equalsIgnoreCase("https") ? 443 : 80)
                            : uri.getPort();
            return scheme.toLowerCase(Locale.ROOT)
                    + "://"
                    + uri.getHost().toLowerCase(Locale.ROOT)
                    + ":"
                    + port;
        } catch (IllegalArgumentException malformed) {
            throw PrototypeReviewService.denied();
        }
    }

    public String contextCookie(ServerWebExchange exchange) {
        List<HttpCookie> values = exchange.getRequest().getCookies().get(COOKIE);
        return PrototypeReviewKeys.cookie(
                values != null && values.size() == 1 ? values.getFirst().getValue() : null);
    }

    public String unsafeCookie(ServerWebExchange exchange) {
        List<HttpCookie> values = exchange.getRequest().getCookies().get(COOKIE);
        if (values == null || values.size() != 1) throw PrototypeReviewService.denied();
        String raw = values.getFirst().getValue();
        if (!raw.matches("[A-Za-z0-9_-]{43}")
                || !PrototypeReviewKeys.matches(
                        PrototypeReviewKeys.csrf(raw),
                        exchange.getRequest().getHeaders().getFirst("X-Review-CSRF")))
            throw PrototypeReviewService.denied();
        return raw;
    }

    public void cookie(ServerWebExchange exchange, String token, String raw) {
        exchange.getResponse()
                .addCookie(
                        ResponseCookie.from(COOKIE, raw)
                                .httpOnly(true)
                                .secure(properties.isCookieSecure())
                                .sameSite("Strict")
                                .path("/api/prototype-hub/public/shares/" + token + "/review")
                                .maxAge(PrototypeReviewService.SESSION_TTL / 1000)
                                .build());
    }
}
