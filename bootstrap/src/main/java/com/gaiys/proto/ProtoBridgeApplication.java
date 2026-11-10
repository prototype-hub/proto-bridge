package com.gaiys.proto;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.authorization.basic.configuration.EnableAopAuthorize;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.logging.events.AccessLoggerAfterEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Starts the reactive application and activates existing repository, authorization and job support.
 *
 * <p>Spring owns component lifecycle. JDBC, Redis and Elasticsearch client defaults are excluded
 * because schema synchronization uses EasyORM/R2DBC and infrastructure clients are configured
 * explicitly.
 */
@SpringBootApplication(
        scanBasePackages = "com.gaiys.proto",
        exclude = DataSourceAutoConfiguration.class,
        excludeName = {
            // ManagementWebConfiguration 继承官方工厂，保留认证接口并统一管理分页校验。
            "org.hswebframework.web.system.authorization.defaults.configuration.AuthorizationWebAutoConfiguration",
            "org.hswebframework.web.system.authorization.defaults.configuration.AuthorizationWebAutoConfiguration.WebFluxAuthorizationConfiguration",
            "org.hswebframework.web.dictionary.configuration.DictionaryAutoConfiguration",
            "org.hswebframework.web.dictionary.configuration.DictionaryAutoConfiguration.DictionaryWebFluxConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
            "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration",
            "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration"
        })
@EnableScheduling
@EnableCaching
@EnableEasyormRepository("com.gaiys.proto.**.entity")
@EnableAopAuthorize
@Slf4j
public class ProtoBridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProtoBridgeApplication.class, args);
    }

    @EventListener
    public void completedAccess(AccessLoggerAfterEvent event) {
        var access = event.getLogger();
        // 分享等能力凭据可能位于 URL 路径中，访问摘要不记录原始地址。
        log.info("{} => {} - {}", access.getIp(), access.getDescribe(), access.getAction());
    }
}
