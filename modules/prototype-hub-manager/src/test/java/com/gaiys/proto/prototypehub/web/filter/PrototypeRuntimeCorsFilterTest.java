package com.gaiys.proto.prototypehub.web.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PrototypeRuntimeCorsFilterTest {
    private final PrototypeRuntimeCorsFilter filter = new PrototypeRuntimeCorsFilter();

    @Test
    void opaquePrototypeCanReadRuntimeWithoutCredentialsAndStillReachesTheKeyCheck() {
        for (String path :
                List.of(
                        "/prototype-hub/runtime/version/key/file.json",
                        "/prototype-hub/public/shares/token/key/runtime/font.woff2")) {
            var exchange =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.get("http://localhost" + path)
                                    .header("Origin", "null"));
            AtomicBoolean checked = new AtomicBoolean();
            StepVerifier.create(
                            filter.filter(
                                    exchange,
                                    request -> {
                                        checked.set(true);
                                        request.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                                        return Mono.empty();
                                    }))
                    .verifyComplete();
            assertTrue(checked.get());
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
            assertEquals("null", exchange.getResponse().getHeaders().getAccessControlAllowOrigin());
            assertNull(
                    exchange.getResponse()
                            .getHeaders()
                            .getFirst("Access-Control-Allow-Credentials"));
        }
    }

    @Test
    void runtimeSupportsResourcePreflight() {
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.options(
                                        "http://localhost/prototype-hub/runtime/v/k/file.json")
                                .header("Origin", "null")
                                .header("Access-Control-Request-Method", "GET")
                                .header("Access-Control-Request-Headers", "Range"));
        StepVerifier.create(
                        filter.filter(
                                exchange,
                                request ->
                                        Mono.error(
                                                new AssertionError(
                                                        "Preflight must not read files"))))
                .verifyComplete();
        assertEquals("null", exchange.getResponse().getHeaders().getAccessControlAllowOrigin());
        assertTrue(
                exchange.getResponse()
                        .getHeaders()
                        .getAccessControlAllowHeaders()
                        .contains("Range"));
    }

    @Test
    void narrowRuntimePolicyWorksBeforeTheConfiguredGlobalCorsFilter() {
        var source = new UrlBasedCorsConfigurationSource();
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("https://frontend.example"));
        config.setAllowedMethods(List.of("GET"));
        source.registerCorsConfiguration("/**", config);
        var global = new CorsWebFilter(source);
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get(
                                        "http://localhost/prototype-hub/runtime/v/k/file.json")
                                .header("Origin", "null"));
        AtomicBoolean reachedResource = new AtomicBoolean();
        StepVerifier.create(
                        filter.filter(
                                exchange,
                                request ->
                                        global.filter(
                                                request,
                                                resource -> {
                                                    reachedResource.set(true);
                                                    return Mono.empty();
                                                })))
                .verifyComplete();
        assertTrue(reachedResource.get());
        assertEquals("null", exchange.getResponse().getHeaders().getAccessControlAllowOrigin());
    }

    @Test
    void unrelatedManagementPreflightRemainsOwnedByGlobalCors() {
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.options("http://localhost/user/detail")
                                .header("Origin", "https://frontend.example")
                                .header("Access-Control-Request-Method", "PUT"));
        AtomicBoolean continued = new AtomicBoolean();
        StepVerifier.create(
                        filter.filter(
                                exchange,
                                request -> {
                                    continued.set(true);
                                    return Mono.empty();
                                }))
                .verifyComplete();
        assertTrue(continued.get());
        assertNull(exchange.getResponse().getHeaders().getAccessControlAllowOrigin());
    }
}
