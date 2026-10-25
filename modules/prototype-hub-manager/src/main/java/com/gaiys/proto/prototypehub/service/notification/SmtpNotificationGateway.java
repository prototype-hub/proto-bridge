package com.gaiys.proto.prototypehub.service.notification;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import javax.net.ssl.SSLException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Boot SMTP 验证码适配器，使用现有有界 FileWork 隔离阻塞协议。
 *
 * <p>不自动重试不确定的投递结果；对外保持通用错误，仅在本地日志记录固定失败类别及异常类型。
 */
@Component
@Slf4j
public class SmtpNotificationGateway implements NotificationGateway {
    private final JavaMailSender sender;
    private final String from;
    private final boolean validFrom;
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
        String configuredFrom =
                environment.getProperty("spring.mail.properties.mail.smtp.from", "");
        String username = environment.getProperty("spring.mail.username", "");
        this.from =
                !configuredFrom.isBlank()
                        ? configuredFrom
                        : !username.isBlank() ? username : "prototype-hub@localhost";
        this.validFrom = validMailbox(from);
        this.work = work;
        this.observations = observations;
        if (sender instanceof JavaMailSenderImpl implementation) {
            var properties = implementation.getJavaMailProperties();
            // YAML空值表示未配置；协议层不能把空字符串当作有效MAIL FROM。
            if (properties.getProperty("mail.smtp.from", "").isBlank())
                properties.setProperty("mail.smtp.from", from);
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
        // 显式发件配置错误不能伪装为发送成功，也不能静默改用另一个身份。
        if (!validFrom)
            return Mono.error(
                    () -> deliveryUnavailable(FailureReason.FROM_ADDRESS_INVALID, List.of()));
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
                                                        throw deliveryUnavailable(failure);
                                                    }
                                                }))
                // JavaMail 异常可包含完整收件地址，不能作为公开异常或保留 cause 进入日志。
                .onErrorMap(
                        error ->
                                error instanceof PrototypeHubException
                                                && "PROTOTYPE_HUB_REVIEW_MAIL_UNAVAILABLE"
                                                        .equals(
                                                                ((PrototypeHubException) error)
                                                                        .getCode())
                                        ? unavailable()
                                        : deliveryUnavailable(error));
    }

    private PrototypeHubException deliveryUnavailable(Throwable failure) {
        var causes = failureCauses(failure);
        FailureReason reason = FailureReason.UNKNOWN;
        for (Throwable cause : causes) {
            FailureReason candidate = failureReason(cause);
            if (candidate.ordinal() < reason.ordinal()) reason = candidate;
        }
        return deliveryUnavailable(reason, causes);
    }

    private PrototypeHubException deliveryUnavailable(
            FailureReason reason, List<Throwable> causes) {
        String protocol = "OTHER";
        int port = -1;
        if (sender instanceof JavaMailSenderImpl implementation) {
            String configured = implementation.getProtocol();
            if ("smtp".equals(configured) || "smtps".equals(configured)) protocol = configured;
            port = implementation.getPort();
        }
        // 原始message/cause/failedMessages均可能含邮箱、验证码或认证数据，只记录有界类型名。
        log.warn(
                "Review verification mail failed: reason={} exceptionClasses={} protocol={} port={}",
                reason,
                causes.stream().map(cause -> cause.getClass().getSimpleName()).distinct().toList(),
                protocol,
                port);
        return unavailable();
    }

    private static boolean validMailbox(String from) {
        try {
            var address = new InternetAddress(from, true);
            address.validate();
            // 当前配置契约只接受单一完整mailbox，避免把显示名称直接写入SMTP envelope。
            return !address.isGroup()
                    && address.getPersonal() == null
                    && from.equals(address.getAddress());
        } catch (AddressException invalid) {
            return false;
        }
    }

    private static List<Throwable> failureCauses(Throwable failure) {
        var pending = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var causes = new ArrayList<Throwable>();
        pending.add(failure);
        // JavaMail可用nextException和逐消息异常代替cause；同时限制循环链和日志大小。
        while (!pending.isEmpty() && causes.size() < 16) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            causes.add(current);
            if (current.getCause() != null && pending.size() < 16)
                pending.addLast(current.getCause());
            if (current instanceof MessagingException messaging
                    && messaging.getNextException() != null
                    && pending.size() < 16) pending.addLast(messaging.getNextException());
            if (current instanceof MailSendException send) {
                for (Exception exception : send.getMessageExceptions()) {
                    if (pending.size() >= 16) break;
                    pending.addLast(exception);
                }
            }
        }
        return causes;
    }

    private static FailureReason failureReason(Throwable failure) {
        if (failure instanceof MailAuthenticationException
                || failure instanceof AuthenticationFailedException)
            return FailureReason.AUTHENTICATION;
        if (failure instanceof SSLException) return FailureReason.TLS;
        if (failure instanceof SocketTimeoutException) return FailureReason.TIMEOUT;
        if (failure instanceof UnknownHostException) return FailureReason.DNS;
        if (failure instanceof ConnectException || failure instanceof NoRouteToHostException)
            return FailureReason.CONNECTION;
        if (failure instanceof RejectedExecutionException) return FailureReason.WORKER_REJECTED;
        if (failure instanceof MailParseException || failure instanceof MailPreparationException)
            return FailureReason.CLIENT_CONFIGURATION;
        if (failure instanceof MailSendException || failure instanceof SendFailedException)
            return FailureReason.DELIVERY;
        return FailureReason.UNKNOWN;
    }

    /** 更具体的连接/认证原因优先于外层通用投递异常；枚举值同时是固定运维诊断契约。 */
    private enum FailureReason {
        FROM_ADDRESS_INVALID,
        AUTHENTICATION,
        TLS,
        TIMEOUT,
        DNS,
        CONNECTION,
        WORKER_REJECTED,
        CLIENT_CONFIGURATION,
        DELIVERY,
        UNKNOWN
    }

    public static PrototypeHubException unavailable() {
        return new PrototypeHubException(
                "error.prototype-hub.review_mail_unavailable",
                "PROTOTYPE_HUB_REVIEW_MAIL_UNAVAILABLE",
                503);
    }
}
