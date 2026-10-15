package com.gaiys.proto.system.security.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.system.security.properties.CaptchaProperties;
import com.gaiys.proto.system.service.CaptchaService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.test.StepVerifier;

class LoginChallengeTest {
    @Test
    void challengeIsConsumedOnceAndExpires() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ReactiveStringRedisTemplate> provider = mock(ObjectProvider.class);
        var store = new LoginChallengeStore(provider);
        store.put("one", "opaque", Duration.ofSeconds(1)).block();
        StepVerifier.create(store.consume("one")).expectNext("opaque").verifyComplete();
        StepVerifier.create(store.consume("one")).verifyComplete();
        StepVerifier.create(
                        store.put("expiring", "opaque", Duration.ofMillis(10))
                                .then(reactor.core.publisher.Mono.delay(Duration.ofMillis(50)))
                                .then(store.consume("expiring")))
                .verifyComplete();
    }

    @Test
    void captchaCannotBeReplayedAfterWrongAnswer() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ReactiveStringRedisTemplate> provider = mock(ObjectProvider.class);
        var store = new LoginChallengeStore(provider);
        var properties = new CaptchaProperties();
        properties.setEnabled(true);
        var random = mock(java.security.SecureRandom.class);
        when(random.nextInt(anyInt())).thenReturn(0);
        try (var service = new CaptchaService(properties, store, random)) {
            var image = service.image(130, 40).block();
            assertTrue(image.base64().startsWith("data:image/png;base64,"));
            StepVerifier.create(service.verify(image.key(), "wrong"))
                    .expectError(org.hswebframework.web.exception.BusinessException.class)
                    .verify();
            StepVerifier.create(service.verify(image.key(), "AAAAA"))
                    .expectError(org.hswebframework.web.exception.BusinessException.class)
                    .verify();
        }
    }
}
