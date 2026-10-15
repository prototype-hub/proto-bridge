package com.gaiys.proto.system.security.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class LoginChallengeStore {
    private final ReactiveStringRedisTemplate redis;

    private record Value(String text, Duration ttl) {}

    private final Cache<String, Value> local =
            Caffeine.newBuilder()
                    .maximumSize(10000)
                    .expireAfter(
                            new com.github.benmanes.caffeine.cache.Expiry<String, Value>() {
                                public long expireAfterCreate(String key, Value value, long now) {
                                    return value.ttl().toNanos();
                                }

                                public long expireAfterUpdate(
                                        String key, Value value, long now, long current) {
                                    return value.ttl().toNanos();
                                }

                                public long expireAfterRead(
                                        String key, Value value, long now, long current) {
                                    return current;
                                }
                            })
                    .build();

    public LoginChallengeStore(ObjectProvider<ReactiveStringRedisTemplate> redis) {
        this.redis = redis.getIfAvailable();
    }

    public Mono<Void> put(String key, String value, Duration ttl) {
        if (redis != null) return redis.opsForValue().set("proto-login:" + key, value, ttl).then();
        return Mono.fromRunnable(() -> local.put(key, new Value(value, ttl)));
    }

    public Mono<String> consume(String key) {
        if (redis != null) return redis.opsForValue().getAndDelete("proto-login:" + key);
        return Mono.defer(
                () ->
                        Optional.ofNullable(local.asMap().remove(key))
                                .map(stored -> Mono.just(stored.text()))
                                .orElseGet(Mono::empty));
    }
}
