package com.gaiys.proto.system.configuration;

import com.gaiys.proto.system.web.DictionaryController;
import com.gaiys.proto.system.web.DictionaryItemController;
import com.gaiys.proto.system.web.PermissionController;
import org.hswebframework.web.dictionary.configuration.DictionaryProperties;
import org.hswebframework.web.dictionary.webflux.WebfluxDictionaryController;
import org.hswebframework.web.dictionary.webflux.WebfluxDictionaryItemController;
import org.hswebframework.web.system.authorization.defaults.configuration.AuthorizationWebAutoConfiguration;
import org.hswebframework.web.system.authorization.defaults.webflux.WebFluxPermissionController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 保留官方认证控制器工厂，仅替换需要分页校验的权限、字典控制器。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DictionaryProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ManagementWebConfiguration
        extends AuthorizationWebAutoConfiguration.WebFluxAuthorizationConfiguration {
    @Bean
    @Override
    public WebFluxPermissionController webFluxPermissionController() {
        return new PermissionController();
    }

    // 官方字典 WebFlux 工厂不可继承；注册其公开控制器子类，服务和其他自动配置仍沿用官方实现。
    @Bean
    public WebfluxDictionaryController webfluxDictionaryController() {
        return new DictionaryController();
    }

    @Bean
    public WebfluxDictionaryItemController webfluxDictionaryItemController() {
        return new DictionaryItemController();
    }
}
