package com.gaiys.proto.system.web.filter;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class LoginRateLimitTest {
    @Test
    void loginAndShareVerificationAreBoundedAndOtherRequestsProceed() {
        var filter = new LoginRateLimitFilter(2);
        for (String path :
                java.util.List.of(
                        "/authorize/login", "/prototype-hub/public/shares/token/verify")) {
            for (int n = 0; n < 2; n++)
                StepVerifier.create(filter.filter(exchange(path, "127.0.0.1"), e -> Mono.empty()))
                        .verifyComplete();
            StepVerifier.create(filter.filter(exchange(path, "127.0.0.1"), e -> Mono.empty()))
                    .expectErrorMatches(
                            error ->
                                    error instanceof ResponseStatusException status
                                            && status.getStatusCode().value() == 429)
                    .verify();
            StepVerifier.create(filter.filter(exchange(path, "127.0.0.2"), e -> Mono.empty()))
                    .verifyComplete();
        }
        StepVerifier.create(
                        filter.filter(
                                exchange("/prototype-hub/projects", "127.0.0.1"),
                                e -> Mono.empty()))
                .verifyComplete();
        filter.clearExpired();
    }

    @Test
    void publicAndManagementShareChecksShareQuotaWithoutGuardingUnrelatedRequests() {
        var filter = new LoginRateLimitFilter(2);
        var calls = new AtomicInteger();
        String publicPath = "/prototype-hub/public/shares/token/verify";
        String managementPath = "/prototype-hub/shares/id/verify-password";
        for (String path : java.util.List.of(publicPath, managementPath))
            StepVerifier.create(
                            filter.filter(
                                    exchange(path, "127.0.0.1"),
                                    e -> {
                                        calls.incrementAndGet();
                                        return Mono.empty();
                                    }))
                    .verifyComplete();
        assertEquals(2, calls.get());
        for (String path : java.util.List.of(managementPath, publicPath)) {
            var exchange = exchange(path, "127.0.0.1");
            StepVerifier.create(
                            filter.filter(
                                    exchange,
                                    e -> {
                                        calls.incrementAndGet();
                                        return Mono.empty();
                                    }))
                    .expectErrorMatches(
                            error ->
                                    error instanceof ResponseStatusException status
                                            && status.getStatusCode().value() == 429)
                    .verify();
            assertEquals("60", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        }
        assertEquals(2, calls.get());
        for (String path :
                java.util.List.of(
                        publicPath,
                        managementPath,
                        "/prototype-hub/versions/version/shares",
                        "/prototype-hub/projects"))
            StepVerifier.create(
                            filter.filter(
                                    MockServerWebExchange.from(
                                            MockServerHttpRequest.get(path)
                                                    .remoteAddress(
                                                            new InetSocketAddress(
                                                                    "127.0.0.1", 8000))
                                                    .build()),
                                    e -> Mono.empty()))
                    .verifyComplete();
        for (String path :
                java.util.List.of(
                        "/prototype-hub/versions/version/shares",
                        "/prototype-hub/projects",
                        "/authorize/login"))
            StepVerifier.create(filter.filter(exchange(path, "127.0.0.1"), e -> Mono.empty()))
                    .verifyComplete();
        StepVerifier.create(
                        filter.filter(
                                MockServerWebExchange.from(
                                        MockServerHttpRequest.method(
                                                        HttpMethod.DELETE,
                                                        "/prototype-hub/shares/id")
                                                .remoteAddress(
                                                        new InetSocketAddress("127.0.0.1", 8000))
                                                .build()),
                                e -> Mono.empty()))
                .verifyComplete();
        StepVerifier.create(filter.filter(exchange(managementPath, "127.0.0.2"), e -> Mono.empty()))
                .verifyComplete();
    }

    MockServerWebExchange exchange(String path, String peer) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post(path)
                        .remoteAddress(new InetSocketAddress(peer, 8000))
                        .build());
    }
}
