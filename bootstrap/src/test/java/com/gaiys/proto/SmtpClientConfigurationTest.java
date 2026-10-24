package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** 从实际主YAML装配Boot邮件客户端；不连接外部SMTP，也不复制另一套默认配置。 */
class SmtpClientConfigurationTest {
    private ApplicationContextRunner configured() {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                PropertyPlaceholderAutoConfiguration.class,
                                MailSenderAutoConfiguration.class))
                .withInitializer(
                        context -> {
                            try {
                                for (var source :
                                        new YamlPropertySourceLoader()
                                                .load(
                                                        "main-mail-config",
                                                        new ClassPathResource("application.yml")))
                                    context.getEnvironment().getPropertySources().addLast(source);
                            } catch (IOException error) {
                                throw new UncheckedIOException(error);
                            }
                        });
    }

    @Test
    void mainYamlKeepsMailOptionalAndBindsSafeSmtpAndSmtpsClientDefaults() {
        configured()
                .run(
                        context ->
                                assertEquals(
                                        "",
                                        context.getEnvironment().getProperty("spring.mail.host")));
        configured()
                .withPropertyValues("spring.mail.host=mail.example.invalid")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var sender = context.getBean(JavaMailSenderImpl.class);
                            assertEquals("mail.example.invalid", sender.getHost());
                            assertEquals(587, sender.getPort());
                            assertEquals("smtp", sender.getProtocol());
                            assertEquals("UTF-8", sender.getDefaultEncoding());
                            var values = sender.getJavaMailProperties();
                            assertEquals("true", values.getProperty("mail.smtp.auth"));
                            assertEquals("true", values.getProperty("mail.smtp.starttls.enable"));
                            assertEquals("true", values.getProperty("mail.smtp.starttls.required"));
                            assertEquals("false", values.getProperty("mail.smtp.ssl.enable"));
                            assertEquals("true", values.getProperty("mail.smtps.ssl.enable"));
                            for (String protocol : new String[] {"smtp", "smtps"}) {
                                assertEquals(
                                        "true",
                                        values.getProperty(
                                                "mail." + protocol + ".ssl.checkserveridentity"));
                                assertEquals(
                                        "localhost",
                                        values.getProperty("mail." + protocol + ".localhost"));
                                for (String timeout :
                                        new String[] {
                                            "connectiontimeout", "timeout", "writetimeout"
                                        })
                                    assertTrue(
                                            Integer.parseInt(
                                                            values.getProperty(
                                                                    "mail." + protocol + "."
                                                                            + timeout))
                                                    > 0);
                            }
                        });
    }

    @Test
    void explicitSmtpsProtocolPortAndIdentityUseTheSameConfiguredClient() {
        configured()
                .withInitializer(
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new SystemEnvironmentPropertySource(
                                                        "smtp-test-variables",
                                                        Map.of(
                                                                "SMTP_HOST", "mail.example.invalid",
                                                                "SMTP_PROTOCOL", "smtps",
                                                                "SMTP_PORT", "465",
                                                                "SMTP_FROM",
                                                                        "alias@example.invalid",
                                                                "SMTP_LOCALHOST",
                                                                        "secure.example.invalid"))))
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var sender = context.getBean(JavaMailSenderImpl.class);
                            assertEquals("smtps", sender.getProtocol());
                            assertEquals(465, sender.getPort());
                            assertEquals("mail.example.invalid", sender.getHost());
                            assertEquals(
                                    "alias@example.invalid",
                                    sender.getJavaMailProperties().getProperty("mail.smtp.from"));
                            assertEquals(
                                    "secure.example.invalid",
                                    sender.getJavaMailProperties()
                                            .getProperty("mail.smtps.localhost"));
                            assertEquals(
                                    "true",
                                    sender.getJavaMailProperties()
                                            .getProperty("mail.smtps.ssl.enable"));
                            assertEquals(
                                    "false",
                                    sender.getJavaMailProperties()
                                            .getProperty("mail.smtps.starttls.enable"));
                        });
    }

    @Test
    void standardMailEnvironmentAndExactMapOverridesWinOverMainYamlDefaults() {
        configured()
                .withInitializer(
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new SystemEnvironmentPropertySource(
                                                        "mail-test-environment",
                                                        Map.of(
                                                                "SPRING_MAIL_HOST", "127.0.0.1",
                                                                "SPRING_MAIL_PORT", "2525",
                                                                "SPRING_MAIL_USERNAME",
                                                                        "identity@example.invalid",
                                                                "SPRING_MAIL_PASSWORD",
                                                                        "fixture-only-not-used"))))
                .withPropertyValues(
                        "spring.mail.properties.mail.smtp.auth=false",
                        "spring.mail.properties.mail.smtp.starttls.enable=false",
                        "spring.mail.properties.mail.smtp.starttls.required=false",
                        "spring.mail.properties.mail.smtp.ssl.enable=false",
                        "spring.mail.properties.mail.smtp.timeout=2345",
                        "spring.mail.properties.mail.smtp.localhost=mail.example.invalid",
                        "spring.mail.properties.mail.smtp.from=sender@example.invalid",
                        "spring.mail.properties.mail.from=message@example.invalid")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var sender = context.getBean(JavaMailSenderImpl.class);
                            assertEquals("127.0.0.1", sender.getHost());
                            assertEquals(2525, sender.getPort());
                            assertEquals("identity@example.invalid", sender.getUsername());
                            var values = sender.getJavaMailProperties();
                            assertEquals("false", values.getProperty("mail.smtp.auth"));
                            assertEquals("false", values.getProperty("mail.smtp.starttls.enable"));
                            assertEquals(
                                    "false", values.getProperty("mail.smtp.starttls.required"));
                            assertEquals("2345", values.getProperty("mail.smtp.timeout"));
                            assertEquals(
                                    "mail.example.invalid",
                                    values.getProperty("mail.smtp.localhost"));
                            assertEquals(
                                    "sender@example.invalid", values.getProperty("mail.smtp.from"));
                            assertEquals(
                                    "message@example.invalid", values.getProperty("mail.from"));
                        });
    }
}
