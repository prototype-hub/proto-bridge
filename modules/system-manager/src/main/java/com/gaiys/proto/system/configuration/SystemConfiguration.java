package com.gaiys.proto.system.configuration;

import com.gaiys.proto.system.security.encoder.StrongPasswordEncoder;
import com.gaiys.proto.system.service.SecureReactiveUserService;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.system.authorization.api.PasswordEncoder;
import org.hswebframework.web.system.authorization.api.PasswordValidator;
import org.hswebframework.web.system.authorization.defaults.service.DefaultReactiveUserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableEasyormRepository({
    "org.hswebframework.web.system.authorization.api.entity",
    "org.hswebframework.web.dictionary.entity"
})
public class SystemConfiguration {
    @Bean
    @Primary
    public org.hswebframework.web.authorization.ReactiveAuthenticationInitializeService
            managementAuthenticationInitializer(
                    org.hswebframework.web.system.authorization.defaults.service
                                    .DefaultDimensionService
                            dimensions,
                    org.hswebframework.web.system.authorization.defaults.service
                                    .DefaultAuthorizationSettingService
                            settings,
                    org.hswebframework.web.system.authorization.defaults.service
                                    .DefaultPermissionService
                            permissions) {
        return new com.gaiys.proto.system.security.initializer.ManagementAuthenticationInitializer(
                dimensions, settings, permissions);
    }

    @Bean(name = "captchaRandom")
    public java.security.SecureRandom captchaRandom() {
        return new java.security.SecureRandom();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new StrongPasswordEncoder();
    }

    @Bean
    public PasswordValidator passwordValidator() {
        return password -> {
            if (password == null || password.length() < 12 || password.length() > 128) {
                throw new IllegalArgumentException("validation.password_invalid");
            }
        };
    }

    @Bean(destroyMethod = "dispose")
    @Primary
    public DefaultReactiveUserService secureUserService(PasswordEncoder encoder) {
        return new SecureReactiveUserService(encoder);
    }

    @Bean
    public static org.springframework.beans.factory.config.BeanFactoryPostProcessor jsonSafety() {
        System.setProperty("fastjson.parser.safeMode", "true");
        com.alibaba.fastjson.parser.ParserConfig.getGlobalInstance().setSafeMode(true);
        return beanFactory -> {};
    }
}
