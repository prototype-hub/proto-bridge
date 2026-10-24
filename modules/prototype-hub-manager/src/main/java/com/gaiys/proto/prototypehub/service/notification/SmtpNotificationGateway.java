package com.gaiys.proto.prototypehub.service.notification;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Boot SMTP 适配器；阻塞协议使用现有有界 FileWork，并且不自动重试不确定的投递结果。 */
@Component
public class SmtpNotificationGateway implements NotificationGateway {
    private final JavaMailSender sender;
    private final String from;
    private final FileWork work;
    private final ObservationRegistry observations;

    public SmtpNotificationGateway(
            ObjectProvider<JavaMailSender> senders,
            Environment environment,
            FileWork work,
            ObservationRegistry observations) {
        this.sender =
                environment.getProperty("spring.mail.host", "").isBlank()
                        ? null
                        : senders.getIfAvailable();
        this.from =
                environment.getProperty(
                        "spring.mail.properties.mail.smtp.from",
                        environment.getProperty("spring.mail.username", "prototype-hub@localhost"));
        this.work = work;
        this.observations = observations;
        if (sender instanceof JavaMailSenderImpl implementation) {
            var properties = implementation.getJavaMailProperties();
            // Message-ID默认另走InternetAddress本机DNS；显式本地身份覆盖该分支，保留用户配置。
            properties.putIfAbsent("mail.from", from);
            for (String protocol : new String[] {"smtp", "smtps"}) {
                for (String timeout :
                        new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
                    String key = "mail." + protocol + "." + timeout;
                    try {
                        if (Integer.parseInt(properties.getProperty(key, "0")) <= 0)
                            properties.setProperty(key, "5000");
                    } catch (NumberFormatException invalid) {
                        properties.setProperty(key, "5000");
                    }
                }
                properties.putIfAbsent("mail." + protocol + ".ssl.checkserveridentity", "true");
                // Angus缺省EHLO查询本机反向DNS，套接字超时不覆盖它；允许运维显式提供FQDN。
                properties.putIfAbsent("mail." + protocol + ".localhost", "localhost");
            }
        }
    }

    @Override
    public boolean available() {
        return sender != null;
    }

    @Override
    public Mono<Void> sendVerification(String email, String code) {
        if (!available()) return Mono.error(unavailable());
        return work.run(
                        () ->
                                Observation.createNotStarted("prototype.review.send", observations)
                                        .observe(
                                                () -> {
                                                    SimpleMailMessage message =
                                                            new SimpleMailMessage();
                                                    message.setFrom(from);
                                                    message.setTo(email);
                                                    message.setSubject(
                                                            "PrototypeHub email verification");
                                                    message.setText(
                                                            "Your PrototypeHub verification code is "
                                                                    + code
                                                                    + ". It expires in 5 minutes.\n"
                                                                    + "This verifies email ownership only; ignore this message if you did not request it.");
                                                    try {
                                                        sender.send(message);
                                                    } catch (RuntimeException failure) {
                                                        // 先净化再交给Observation，避免协议异常中的收件地址进入追踪。
                                                        throw unavailable();
                                                    }
                                                }))
                // JavaMail 异常可包含完整收件地址，不能作为公开异常或保留 cause 进入日志。
                .onErrorMap(error -> unavailable());
    }

    public static PrototypeHubException unavailable() {
        return new PrototypeHubException(
                "error.prototype-hub.review_mail_unavailable",
                "PROTOTYPE_HUB_REVIEW_MAIL_UNAVAILABLE",
                503);
    }
}
