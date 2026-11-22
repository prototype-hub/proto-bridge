package com.gaiys.proto.system.web.handler;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebHandler;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ApiErrorResponseTest {
    private static final MediaType JAVASCRIPT = MediaType.parseMediaType("text/javascript");

    @Test
    void resourceFailureAfterFirstBytesPropagatesOriginalErrorWithoutASecondJsonResponse() {
        try (var context = new AnnotationConfigApplicationContext(WebConfiguration.class)) {
            var fixture = context.getBean(Resources.class);
            IOException failure = new IOException("fixture read failure");
            fixture.resource = failingResource(failure, true);
            var exchange = exchange("/resource");
            var sent = new ByteArrayOutputStream();
            exchange.getResponse()
                    .setWriteHandler(
                            body ->
                                    body.doOnNext(
                                                    buffer -> {
                                                        byte[] bytes =
                                                                new byte
                                                                        [buffer
                                                                                .readableByteCount()];
                                                        buffer.read(bytes);
                                                        sent.writeBytes(bytes);
                                                        DataBufferUtils.release(buffer);
                                                    })
                                            .then());

            StepVerifier.create(context.getBean("webHandler", WebHandler.class).handle(exchange))
                    .expectErrorSatisfies(error -> assertSame(failure, error))
                    .verify();

            assertTrue(exchange.getResponse().isCommitted());
            assertEquals(JAVASCRIPT, exchange.getResponse().getHeaders().getContentType());
            assertEquals("first", sent.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void resourceFailureBeforeFirstBytesBecomesJsonEvenForJavascriptAccept() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(WebConfiguration.class)) {
            context.getBean(Resources.class).resource =
                    failingResource(new IOException("fixture read failure"), false);
            var exchange =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.get("/resource").accept(JAVASCRIPT));
            context.getBean("webHandler", WebHandler.class).handle(exchange).block();
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
            assertEquals(
                    MediaType.APPLICATION_JSON,
                    exchange.getResponse().getHeaders().getContentType());
            assertEquals(
                    500,
                    new ObjectMapper()
                            .readTree(exchange.getResponse().getBodyAsString().block())
                            .path("status")
                            .asInt());
        }
    }

    @Test
    void cancellingResourceAfterFirstBufferClosesInputAndKeepsResourceResponse() {
        try (var context = new AnnotationConfigApplicationContext(WebConfiguration.class)) {
            var closed = new AtomicBoolean();
            context.getBean(Resources.class).resource =
                    new ByteArrayResource(new byte[32 * 1024]) {
                        @Override
                        public InputStream getInputStream() {
                            return new FilterInputStream(new ByteArrayInputStream(getByteArray())) {
                                @Override
                                public void close() throws IOException {
                                    closed.set(true);
                                    super.close();
                                }
                            };
                        }
                    };
            var exchange = exchange("/resource");
            exchange.getResponse()
                    .setWriteHandler(
                            body -> body.take(1).doOnNext(DataBufferUtils::release).then());
            context.getBean("webHandler", WebHandler.class).handle(exchange).block();
            assertTrue(closed.get());
            assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
            assertEquals(JAVASCRIPT, exchange.getResponse().getHeaders().getContentType());
        }
    }

    @Test
    void normalJavascriptAndDirectDownloadKeepOriginalBytesAndContentType() {
        try (var context = new AnnotationConfigApplicationContext(WebConfiguration.class)) {
            for (String path : List.of("/resource", "/direct")) {
                var exchange = exchange(path);
                context.getBean("webHandler", WebHandler.class).handle(exchange).block();
                assertEquals(JAVASCRIPT, exchange.getResponse().getHeaders().getContentType());
                assertEquals("first", exchange.getResponse().getBodyAsString().block());
            }
        }
    }

    @Test
    void controllerFailureBeforeCommitProducesJsonAndRetainsProtocolHeaders() {
        try (var context = new AnnotationConfigApplicationContext(WebConfiguration.class)) {
            var exchange =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.get("/before-write").accept(JAVASCRIPT));
            context.getBean("webHandler", WebHandler.class).handle(exchange).block();
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
            assertEquals(
                    MediaType.APPLICATION_JSON,
                    exchange.getResponse().getHeaders().getContentType());
            assertEquals(
                    "3", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            assertContentHeadersCleared(exchange);
            assertEquals(
                    503,
                    new ObjectMapper()
                            .readTree(exchange.getResponse().getBodyAsString().block())
                            .path("status")
                            .asInt());
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void outerErrorBoundaryReplacesPreparedResourceHeadersButKeepsOriginalErrorStatus()
            throws Exception {
        var exchange = exchange("/outer");
        prepareResourceHeaders(exchange.getResponse());
        var failure = unavailable();
        new ApiErrorWebHandler(new ObjectMapper()).handle(exchange, failure).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals(
                MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
        assertEquals("3", exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertContentHeadersCleared(exchange);
        assertEquals(
                503,
                new ObjectMapper()
                        .readTree(exchange.getResponse().getBodyAsString().block())
                        .path("status")
                        .asInt());
    }

    @Test
    void ordinaryIoFailuresStayVisibleAfterCommitAndOnlyKnownClientDisconnectsComplete() {
        var handler = new ApiErrorWebHandler(new ObjectMapper());
        var exchange = exchange("/outer");
        exchange.getResponse().setComplete().block();
        IOException failure = new IOException("fixture disk failure");
        StepVerifier.create(handler.handle(exchange, failure))
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify();
        StepVerifier.create(handler.handle(exchange, new IOException("Broken pipe")))
                .verifyComplete();
        var upstreamFailure =
                new WebClientRequestException(
                        new IOException("Connection reset by peer"),
                        HttpMethod.GET,
                        URI.create("https://example.invalid"),
                        new HttpHeaders());
        StepVerifier.create(handler.handle(exchange, upstreamFailure))
                .expectErrorSatisfies(error -> assertSame(upstreamFailure, error))
                .verify();
        assertNull(exchange.getResponse().getHeaders().getContentType());
    }

    @Test
    void clientDisconnectBeforeCommitDoesNotWriteAnErrorEnvelope() {
        var exchange = exchange("/outer");
        StepVerifier.create(
                        new ApiErrorWebHandler(new ObjectMapper())
                                .handle(exchange, new IOException("Broken pipe")))
                .verifyComplete();
        assertFalse(exchange.getResponse().isCommitted());
        assertNull(exchange.getResponse().getHeaders().getContentType());
        try (var context = new AnnotationConfigApplicationContext(WebConfiguration.class)) {
            context.getBean(Resources.class).resource =
                    failingResource(new IOException("Broken pipe"), false);
            var resourceExchange = exchange("/resource");
            StepVerifier.create(
                            context.getBean("webHandler", WebHandler.class)
                                    .handle(resourceExchange))
                    .verifyComplete();
            assertFalse(resourceExchange.getResponse().isCommitted());
            assertNull(resourceExchange.getResponse().getHeaders().getContentType());
        }
    }

    @Test
    void adviceRejectsCommittedResponsesRatherThanCreatingAnotherEnvelope() {
        var exchange = exchange("/resource");
        prepareResourceHeaders(exchange.getResponse());
        exchange.getResponse().setComplete().block();
        IOException failure = new IOException("fixture disk failure");
        var propagated =
                assertThrows(
                        IOException.class,
                        () -> new ApiExceptionHandler().handle(failure, Locale.ENGLISH, exchange));
        assertSame(failure, propagated);
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path));
    }

    private static void prepareResourceHeaders(ServerHttpResponse response) {
        var headers = response.getHeaders();
        headers.setContentType(JAVASCRIPT);
        headers.setContentLength(999);
        headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=fixture.js");
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes 0-4/999");
    }

    private static void assertContentHeadersCleared(MockServerWebExchange exchange) {
        for (String header :
                List.of(
                        HttpHeaders.CONTENT_ENCODING,
                        HttpHeaders.CONTENT_DISPOSITION,
                        HttpHeaders.CONTENT_RANGE))
            assertFalse(exchange.getResponse().getHeaders().containsKey(header), header);
        assertNotEquals(999, exchange.getResponse().getHeaders().getContentLength());
    }

    private static ResponseStatusException unavailable() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "3");
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE) {
            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    private static Resource failingResource(IOException failure, boolean emitFirst) {
        return new AbstractResource() {
            @Override
            public String getDescription() {
                return "fixture";
            }

            @Override
            public long contentLength() {
                return 100;
            }

            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    boolean first = emitFirst;

                    @Override
                    public int read() throws IOException {
                        throw failure;
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        if (!first) throw failure;
                        first = false;
                        byte[] chunk = "first".getBytes(StandardCharsets.UTF_8);
                        System.arraycopy(chunk, 0, bytes, offset, chunk.length);
                        return chunk.length;
                    }
                };
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    @Import(ApiExceptionHandler.class)
    static class WebConfiguration {
        @Bean
        Resources resources() {
            return new Resources();
        }
    }

    @RestController
    static class Resources {
        Resource resource = new ByteArrayResource("first".getBytes(StandardCharsets.UTF_8));

        @GetMapping("/resource")
        Mono<ResponseEntity<Resource>> resource() {
            return Mono.just(ResponseEntity.ok().contentType(JAVASCRIPT).body(resource));
        }

        @GetMapping("/direct")
        Mono<Void> direct(ServerHttpResponse response) {
            response.getHeaders().setContentType(JAVASCRIPT);
            return response.writeWith(
                    Mono.just(
                            response.bufferFactory()
                                    .wrap("first".getBytes(StandardCharsets.UTF_8))));
        }

        @GetMapping("/before-write")
        Mono<Void> beforeWrite(ServerHttpResponse response) {
            prepareResourceHeaders(response);
            return Mono.error(unavailable());
        }
    }
}
