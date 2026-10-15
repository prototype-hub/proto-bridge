package com.gaiys.proto.configuration.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.hswebframework.web.api.crud.entity.EntityFactory;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.MethodParameter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.DecoderHttpMessageReader;
import org.springframework.http.codec.json.Jackson2CodecSupport;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.annotation.RequestBodyMethodArgumentResolver;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class NullSafeJsonDecoderTest {
    final EntityFactory factory = mock(EntityFactory.class);
    final NullSafeJsonDecoder decoder = new NullSafeJsonDecoder(factory, new ObjectMapper());

    Mono<Object> resolve(String method, String json) throws NoSuchMethodException {
        return resolve(
                method,
                Mono.just(
                        DefaultDataBufferFactory.sharedInstance.wrap(
                                json.getBytes(StandardCharsets.UTF_8))));
    }

    Mono<Object> resolve(String method, Publisher<DataBuffer> body) throws NoSuchMethodException {
        var resolver =
                new RequestBodyMethodArgumentResolver(
                        List.of(new DecoderHttpMessageReader<>(decoder)),
                        new ReactiveAdapterRegistry());
        var parameter = new MethodParameter(Bodies.class.getDeclaredMethod(method, Map.class), 0);
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/body")
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(body));
        return resolver.resolveArgument(parameter, new BindingContext(), exchange);
    }

    @Test
    void springRequiredBodiesRejectLiteralNullAndAnEmptyStreamAs400() throws Exception {
        for (String json : List.of("null", "")) {
            StepVerifier.create(resolve("required", json))
                    .expectErrorSatisfies(
                            error ->
                                    assertEquals(
                                            400,
                                            assertInstanceOf(ServerWebInputException.class, error)
                                                    .getStatusCode()
                                                    .value()))
                    .verify();
        }
        StepVerifier.create(resolve("required", Flux.empty()))
                .expectErrorSatisfies(
                        error ->
                                assertEquals(
                                        400,
                                        assertInstanceOf(ServerWebInputException.class, error)
                                                .getStatusCode()
                                                .value()))
                .verify();
    }

    @Test
    void springOptionalBodiesRemainAbsentButAnEmptyObjectIsStillARealBody() throws Exception {
        StepVerifier.create(resolve("optional", "null")).verifyComplete();
        StepVerifier.create(resolve("optional", Flux.empty())).verifyComplete();
        StepVerifier.create(resolve("required", "{}")).expectNext(Map.of()).verifyComplete();
        StepVerifier.create(resolve("optional", "{}")).expectNext(Map.of()).verifyComplete();
    }

    @Test
    void malformedJsonRetainsSpringsDecodingErrorInsteadOfBecomingAnAbsentBody() throws Exception {
        StepVerifier.create(resolve("optional", "{not-json}"))
                .expectError(ServerWebInputException.class)
                .verify();
        StepVerifier.create(resolve("optional", " "))
                .expectError(ServerWebInputException.class)
                .verify();
        // 官方reader对已提供但没有合法JSON token的buffer仍报400，不吞掉解析异常。
        StepVerifier.create(resolve("optional", ""))
                .expectError(ServerWebInputException.class)
                .verify();
    }

    @Test
    void officialEntityFactoryAndJsonViewHintsStillSelectTheOriginalObjectReader() {
        doReturn(ConcreteBody.class).when(factory).getInstanceType(AbstractBody.class, false);
        var buffer =
                DefaultDataBufferFactory.sharedInstance.wrap(
                        "{\"name\":\"Visible\",\"secret\":\"Hidden\"}"
                                .getBytes(StandardCharsets.UTF_8));
        StepVerifier.create(
                        decoder.decodeToMono(
                                Mono.just(buffer),
                                ResolvableType.forClass(AbstractBody.class),
                                MediaType.APPLICATION_JSON,
                                Map.of(Jackson2CodecSupport.JSON_VIEW_HINT, PublicView.class)))
                .assertNext(
                        value -> {
                            ConcreteBody body = assertInstanceOf(ConcreteBody.class, value);
                            assertEquals("Visible", body.name);
                            assertNull(body.secret);
                        })
                .verifyComplete();
    }

    static class Bodies {
        void required(@RequestBody Map<String, Object> body) {}

        void optional(@RequestBody(required = false) Map<String, Object> body) {}
    }

    public interface PublicView {}

    public interface InternalView extends PublicView {}

    public abstract static class AbstractBody {}

    public static class ConcreteBody extends AbstractBody {
        @JsonView(PublicView.class)
        public String name;

        @JsonView(InternalView.class)
        public String secret;
    }
}
