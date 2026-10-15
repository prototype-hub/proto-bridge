package com.gaiys.proto.system.configuration.registration;

import com.gaiys.proto.system.service.NativeDimensionUserService;
import org.hswebframework.web.system.authorization.defaults.service.DefaultDimensionUserService;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.core.type.MethodMetadata;
import org.springframework.stereotype.Component;

/** 在实例化前精确替换已安装官方默认服务，避免两个实例重复发布成员及缓存事件。 */
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class NativeDimensionUserServiceRegistration implements BeanDefinitionRegistryPostProcessor {
    private static final String BEAN_NAME = "defaultDimensionUserService";
    private static final String FACTORY_CLASS =
            "org.hswebframework.web.system.authorization.defaults.configuration."
                    + "AuthorizationServiceAutoConfiguration$ReactiveAuthorizationServiceAutoConfiguration";

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        if (!registry.containsBeanDefinition(BEAN_NAME)) return;
        BeanDefinition original = registry.getBeanDefinition(BEAN_NAME);
        if (!(original instanceof AnnotatedBeanDefinition annotated)) return;
        MethodMetadata factory = annotated.getFactoryMethodMetadata();
        if (factory == null
                || !BEAN_NAME.equals(factory.getMethodName())
                || !BEAN_NAME.equals(original.getFactoryMethodName())
                || !FACTORY_CLASS.equals(factory.getDeclaringClassName())
                || !DefaultDimensionUserService.class.getName().equals(factory.getReturnTypeName()))
            return;

        // 官方 5.0.1 的 @Bean 没有 MissingBean 条件；同名替换定义而非另加 Primary 服务。
        // 本处理器无资源依赖，配置解析完成后运行；资源注入和官方继承事件仍由容器处理。
        RootBeanDefinition replacement = new RootBeanDefinition(NativeDimensionUserService.class);
        replacement.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
        replacement.setScope(original.getScope());
        replacement.setLazyInit(original.isLazyInit());
        replacement.setAutowireCandidate(original.isAutowireCandidate());
        replacement.setPrimary(original.isPrimary());
        replacement.setDependsOn(original.getDependsOn());
        replacement.setRole(original.getRole());
        replacement.setDescription(original.getDescription());
        replacement.setSource(original.getSource());
        replacement.getPropertyValues().addPropertyValues(original.getPropertyValues());
        if (original instanceof AbstractBeanDefinition definition) {
            replacement.copyQualifiersFrom(definition);
            replacement.setInitMethodNames(definition.getInitMethodNames());
            replacement.setDestroyMethodNames(definition.getDestroyMethodNames());
        }
        registry.removeBeanDefinition(BEAN_NAME);
        registry.registerBeanDefinition(BEAN_NAME, replacement);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}
}
