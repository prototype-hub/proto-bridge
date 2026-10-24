package com.gaiys.proto.prototypehub.web.security;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeReviewProperties;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class ReviewRequestSecurityTest {
    @Test
    void defaultOriginUsesSchemeHostAndEffectivePort() {
        var guard = new ReviewRequestSecurity(new PrototypeReviewProperties());
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("https://platform.test/api/review")
                                .header("Origin", "https://PLATFORM.test:443"));
        assertDoesNotThrow(() -> guard.origin(exchange));
        assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
    }

    @Test
    void configuredOriginWorksThroughHostRewritingButDoesNotTrustBackendOrigin() {
        var props = new PrototypeReviewProperties();
        props.setTrustedOrigin("https://review.example.invalid/");
        var guard = new ReviewRequestSecurity(props);
        assertDoesNotThrow(
                () ->
                        guard.origin(
                                MockServerWebExchange.from(
                                        MockServerHttpRequest.post(
                                                        "http://localhost:8080/api/review")
                                                .header(
                                                        "Origin",
                                                        "https://review.example.invalid"))));
        assertThrows(
                PrototypeHubException.class,
                () ->
                        guard.origin(
                                MockServerWebExchange.from(
                                        MockServerHttpRequest.post(
                                                        "http://localhost:8080/api/review")
                                                .header("Origin", "http://localhost:8080"))));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "https://platform.test/path",
                "https://u@platform.test",
                "https://platform.test?x",
                "https://platform.test#x",
                "https://platform.test:0",
                "https://platform.test:65536",
                "https://other.test"
            })
    void unsafeOriginFailsClosed(String origin) {
        var guard = new ReviewRequestSecurity(new PrototypeReviewProperties());
        assertThrows(
                PrototypeHubException.class,
                () ->
                        guard.origin(
                                MockServerWebExchange.from(
                                        MockServerHttpRequest.post(
                                                        "https://platform.test/api/review")
                                                .header("Origin", origin))));
    }

    @Test
    void missingDuplicateAndCrossSiteOriginsAreRejected() {
        var guard = new ReviewRequestSecurity(new PrototypeReviewProperties());
        assertThrows(
                PrototypeHubException.class,
                () ->
                        guard.origin(
                                MockServerWebExchange.from(
                                        MockServerHttpRequest.post("https://platform.test/a"))));
        assertThrows(
                PrototypeHubException.class,
                () ->
                        guard.origin(
                                MockServerWebExchange.from(
                                        MockServerHttpRequest.post("https://platform.test/a")
                                                .header(
                                                        "Origin",
                                                        "https://platform.test",
                                                        "https://platform.test"))));
        assertThrows(
                PrototypeHubException.class,
                () ->
                        guard.origin(
                                MockServerWebExchange.from(
                                        MockServerHttpRequest.post("https://platform.test/a")
                                                .header("Origin", "https://platform.test")
                                                .header("Sec-Fetch-Site", "cross-site"))));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "https://u@platform.test",
                "https://platform.test/path",
                "https://platform.test?x",
                "https://platform.test//",
                "https://platform.test:65536"
            })
    void invalidTrustedOriginFailsAtStartup(String configured) {
        var props = new PrototypeReviewProperties();
        props.setTrustedOrigin(configured);
        assertThrows(IllegalArgumentException.class, () -> new ReviewRequestSecurity(props));
    }

    @Test
    void csrfIsBoundToOneHttpOnlyCookie() {
        var guard = new ReviewRequestSecurity(new PrototypeReviewProperties());
        String raw = PrototypeReviewKeys.random();
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("https://platform.test/a")
                                .cookie(new HttpCookie(ReviewRequestSecurity.COOKIE, raw))
                                .header("X-Review-CSRF", PrototypeReviewKeys.csrf(raw)));
        assertEquals(raw, guard.unsafeCookie(exchange));
        var wrong =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("https://platform.test/a")
                                .cookie(new HttpCookie(ReviewRequestSecurity.COOKIE, raw))
                                .header(
                                        "X-Review-CSRF",
                                        PrototypeReviewKeys.csrf(PrototypeReviewKeys.random())));
        assertThrows(PrototypeHubException.class, () -> guard.unsafeCookie(wrong));
        guard.cookie(exchange, "share", raw);
        var cookie = exchange.getResponse().getCookies().getFirst(ReviewRequestSecurity.COOKIE);
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals("Strict", cookie.getSameSite());
        assertEquals("/api/prototype-hub/public/shares/share/review", cookie.getPath());
        assertNull(cookie.getDomain());
        assertEquals(28800, cookie.getMaxAge().getSeconds());
    }

    @Test
    void insecureCookieRequiresExplicitLocalConfiguration() {
        var props = new PrototypeReviewProperties();
        props.setCookieSecure(false);
        var guard = new ReviewRequestSecurity(props);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("http://localhost/a"));
        guard.cookie(exchange, "local", PrototypeReviewKeys.random());
        assertFalse(
                exchange.getResponse()
                        .getCookies()
                        .getFirst(ReviewRequestSecurity.COOKIE)
                        .isSecure());
    }
}
