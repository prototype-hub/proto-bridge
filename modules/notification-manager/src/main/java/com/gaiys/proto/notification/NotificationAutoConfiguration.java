package com.gaiys.proto.notification;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 在标准 Boot 邮件客户端之后装配唯一传输；不依赖业务模块或应用的组件扫描索引。
 *
 * @since 1.0.0
 * @see NotificationMailTransport
 * @see BusinessMailPolicy
 */
@AutoConfiguration(after = MailSenderAutoConfiguration.class)
public class NotificationAutoConfiguration {
    @Bean(destroyMethod = "close")
    public SmtpWork notificationSmtpWork() {
        return new SmtpWork();
    }

    @Bean
    public NotificationMailTransport notificationMailTransport(
            ObjectProvider<JavaMailSender> senders,
            Environment environment,
            SmtpWork work,
            ObservationRegistry observations,
            ObjectProvider<BusinessMailPolicy> policies) {
        return new SmtpMailTransport(senders, environment, work, observations, policies);
    }
}
