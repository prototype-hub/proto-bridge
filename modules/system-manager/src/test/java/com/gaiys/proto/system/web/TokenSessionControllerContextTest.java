package com.gaiys.proto.system.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.hswebframework.web.authorization.basic.web.ReactiveUserTokenController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TokenSessionControllerContextTest {
    @Test
    void nonWebContextStartsWithoutTheWebOnlyTokenController() {
        new ApplicationContextRunner()
                .withUserConfiguration(TokenSessionController.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(TokenSessionController.class);
                        });
    }

    @Test
    void reactiveWebContextKeepsTheCurrentTokenResetDelegate() {
        var tokens = mock(ReactiveUserTokenController.class);
        when(tokens.resetToken()).thenReturn(Mono.just(true));
        new ReactiveWebApplicationContextRunner()
                .withUserConfiguration(TokenSessionController.class)
                .withBean(ReactiveUserTokenController.class, () -> tokens)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(TokenSessionController.class);
                            StepVerifier.create(
                                            context.getBean(TokenSessionController.class).reset())
                                    .expectNext(true)
                                    .verifyComplete();
                            verify(tokens).resetToken();
                        });
    }
}
