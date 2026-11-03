package com.gaiys.proto.configuration;

import java.util.Map;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.SimpleAutowireCandidateResolver;
import org.springframework.boot.actuate.autoconfigure.health.CompositeHealthContributorConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.autoconfigure.mail.MailHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.mail.MailHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

/**
 * 将未配置的默认邮件客户端标为UNKNOWN，避免可选SMTP拖低整体健康。
 *
 * <p>仅调整单个标准空配置客户端；原生会话、自定义客户端、多个客户端及显式健康配置沿用Boot检查。 由自动配置在邮件客户端之后创建，不发邮件、不暴露连接配置。
 */
@AutoConfiguration(
        after = MailSenderAutoConfiguration.class,
        before = MailHealthContributorAutoConfiguration.class)
@ConditionalOnClass(JavaMailSenderImpl.class)
@ConditionalOnBean(JavaMailSenderImpl.class)
@ConditionalOnEnabledHealthIndicator("mail")
public class OptionalMailHealthConfiguration
        extends CompositeHealthContributorConfiguration<MailHealthIndicator, JavaMailSenderImpl> {
    public OptionalMailHealthConfiguration() {
        super(MailHealthIndicator::new);
    }

    @Bean
    @ConditionalOnMissingBean(name = {"mailHealthIndicator", "mailHealthContributor"})
    public HealthContributor mailHealthContributor(
            ConfigurableListableBeanFactory beans, Environment environment) {
        Map<String, JavaMailSenderImpl> senders =
                SimpleAutowireCandidateResolver.resolveAutowireCandidates(
                        beans, JavaMailSenderImpl.class);
        if (isUnconfiguredDefaultSender(senders, environment))
            return (HealthIndicator) () -> Health.unknown().withDetail("configured", false).build();
        return createContributor(senders);
    }

    private static boolean isUnconfiguredDefaultSender(
            Map<String, JavaMailSenderImpl> senders, Environment environment) {
        JavaMailSenderImpl sender = senders.get("mailSender");
        // 不把自定义、多客户端或原生Session/JNDI入口当成空配置，保留其真实故障状态。
        if (senders.size() != 1
                || sender == null
                || sender.getClass() != JavaMailSenderImpl.class
                || StringUtils.hasText(environment.getProperty("spring.mail.host"))
                || StringUtils.hasText(environment.getProperty("spring.mail.jndi-name"))
                || StringUtils.hasText(sender.getHost())) return false;
        var session = sender.getSession();
        String protocol = sender.getProtocol();
        if (protocol == null) protocol = session.getProperty("mail.transport.protocol");
        if (protocol == null) protocol = "smtp";
        // 显式空协议是无效配置，不能降级为“未配置”而掩盖错误。
        return StringUtils.hasText(protocol)
                && !StringUtils.hasText(session.getProperty("mail.host"))
                && !StringUtils.hasText(session.getProperty("mail." + protocol + ".host"));
    }
}
