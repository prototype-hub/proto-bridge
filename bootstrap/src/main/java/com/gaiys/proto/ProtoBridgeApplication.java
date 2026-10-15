package com.gaiys.proto;

import org.hswebframework.web.authorization.basic.configuration.EnableAopAuthorize;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
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
public class ProtoBridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProtoBridgeApplication.class, args);
    }
}
