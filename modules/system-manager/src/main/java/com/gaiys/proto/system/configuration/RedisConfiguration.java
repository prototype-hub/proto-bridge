package com.gaiys.proto.system.configuration;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.hswebframework.web.authorization.token.UserTokenManager;
import org.hswebframework.web.authorization.token.redis.RedisUserTokenManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnProperty(prefix = "proto.redis", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RedisProperties.class)
public class RedisConfiguration {
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties p) {
        RedisStandaloneConfiguration server =
                new RedisStandaloneConfiguration(p.getHost(), p.getPort());
        server.setDatabase(p.getDatabase());
        if (StringUtils.hasText(p.getUsername())) server.setUsername(p.getUsername());
        if (StringUtils.hasText(p.getPassword())) server.setPassword(p.getPassword());
        var client =
                LettuceClientConfiguration.builder()
                        .commandTimeout(
                                p.getTimeout() == null ? Duration.ofSeconds(2) : p.getTimeout())
                        .clientOptions(
                                ClientOptions.builder()
                                        .socketOptions(
                                                SocketOptions.builder()
                                                        .connectTimeout(
                                                                p.getConnectTimeout() == null
                                                                        ? Duration.ofSeconds(2)
                                                                        : p.getConnectTimeout())
                                                        .build())
                                        .build());
        if (p.getSsl().isEnabled()) client.useSsl();
        return new LettuceConnectionFactory(server, client.build());
    }

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(
            LettuceConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }

    @Bean
    public ReactiveRedisTemplate<Object, Object> hswebRedisOperations(
            LettuceConnectionFactory factory) {
        // Use the official hsweb serializer contract; store only in a dedicated, trusted Redis
        // database.
        RedisSerializer<Object> keys =
                new org.springframework.data.redis.serializer.GenericToStringSerializer<>(
                        Object.class);
        var context =
                RedisSerializationContext.<Object, Object>newSerializationContext(keys)
                        .value(RedisSerializer.java())
                        .hashKey(RedisSerializer.string())
                        .hashValue(RedisSerializer.java())
                        .build();
        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Bean
    @ConfigurationProperties(prefix = "hsweb.user-token")
    public UserTokenManager redisUserTokenManager(
            ReactiveRedisTemplate<Object, Object> operations, ApplicationEventPublisher publisher) {
        RedisUserTokenManager manager = new RedisUserTokenManager(operations);
        manager.setLocalCache(
                Caffeine.newBuilder()
                        .maximumSize(10000)
                        .expireAfterAccess(Duration.ofMinutes(1))
                        .<String, org.hswebframework.web.authorization.token.redis.SimpleUserToken>
                                build()
                        .asMap());
        manager.setEventPublisher(publisher);
        return manager;
    }
}
