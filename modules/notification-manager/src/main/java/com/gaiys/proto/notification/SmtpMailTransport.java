package com.gaiys.proto.notification;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import reactor.core.publisher.Mono;

/**
 * 一次性 SMTP 传输，复用 Boot 客户端、配置和独立有界工作池。
 *
 * <p>基础验证码固定用途，业务用途每次重新授权；不建立持久队列，不重试未知结果。
 */
@Slf4j
public class SmtpMailTransport implements NotificationMailTransport {
    private final JavaMailSender sender;
    private final String from;
    private final boolean validFrom;
    private final SmtpWork work;
    private final ObservationRegistry observations;
    private final ObjectProvider<BusinessMailPolicy> policies;

    public SmtpMailTransport(
            ObjectProvider<JavaMailSender> senders,
            Environment environment,
            SmtpWork work,
            ObservationRegistry observations,
            ObjectProvider<BusinessMailPolicy> policies) {
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
        this.policies = policies;
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
    public Mono<MailDeliveryResult> sendVerification(String email, String code) {
        if (code == null || !code.matches("[0-9]{6}"))
            return Mono.just(failed("INVALID_MESSAGE", false));
        return send(
                email,
                "PrototypeHub email verification",
                "Your PrototypeHub verification code is "
                        + code
                        + ". It expires in 5 minutes.\n"
                        + "This verifies email ownership only; ignore this message if you did not request it.",
                "auth");
    }

    @Override
    public Mono<MailDeliveryResult> sendBusiness(String recipient, String subject, String body) {
        if (subject == null
                || subject.isBlank()
                || subject.length() > 200
                || subject.indexOf('\r') >= 0
                || subject.indexOf('\n') >= 0
                || body == null
                || body.isBlank()
                || body.length() > 8192) return Mono.just(failed("INVALID_MESSAGE", false));
        // 授权发生在每次订阅，不能缓存许可，也不能借验证码入口传入任意业务主题/正文。
        return Mono.defer(
                        () -> {
                            var policy = policies.getIfAvailable();
                            return policy == null
                                    ? Mono.just(false)
                                    : policy.authorize().thenReturn(true);
                        })
                .onErrorReturn(false)
                .flatMap(
                        authorized ->
                                authorized
                                        ? send(recipient, subject, body, "business")
                                        : Mono.just(failed("BUSINESS_NOT_ALLOWED", false)));
    }

    private Mono<MailDeliveryResult> send(
            String recipient, String subject, String body, String purpose) {
        return Mono.defer(
                () -> {
                    if (!available()) return Mono.just(failed("NOT_CONFIGURED", false));
                    if (!validFrom)
                        return Mono.just(report("FROM_ADDRESS_INVALID", false, List.of()));
                    if (!validMailbox(recipient))
                        return Mono.just(failed("INVALID_MESSAGE", false));
                    return work.call(() -> sendOnce(recipient, subject, body, purpose))
                            // 拒绝入池证明尚未调用 SMTP；除此之外不推测执行进度。
                            .onErrorResume(
                                    RejectedExecutionException.class,
                                    failure ->
                                            Mono.just(
                                                    report(
                                                            "WORKER_REJECTED",
                                                            true,
                                                            List.of(failure))))
                            // 发送后观测等边界的异常也不能伪装为明确未发送或泄露原始 cause。
                            .onErrorResume(
                                    failure ->
                                            Mono.just(
                                                    report(
                                                            "UNKNOWN",
                                                            false,
                                                            List.of(failure),
                                                            true)));
                });
    }

    private MailDeliveryResult sendOnce(
            String recipient, String subject, String body, String purpose) {
        var observation =
                Observation.createNotStarted("notification.smtp.send", observations)
                        .lowCardinalityKeyValue("purpose", purpose);
        return observation.observe(
                () -> {
                    var message = new SimpleMailMessage();
                    message.setFrom(from);
                    message.setTo(recipient);
                    message.setSubject(subject);
                    message.setText(body);
                    MailDeliveryResult result;
                    try {
                        sender.send(message);
                        result = new MailDeliveryResult("accepted", "ACCEPTED", false);
                    } catch (RuntimeException failure) {
                        // 原始协议异常可能含邮箱/验证码/凭据，不能进入 Observation 或结果 cause。
                        result = deliveryResult(failure);
                    }
                    observation.lowCardinalityKeyValue("outcome", result.outcome());
                    observation.lowCardinalityKeyValue("reason", result.reason());
                    return result;
                });
    }

    private MailDeliveryResult deliveryResult(Throwable failure) {
        var causes = failureCauses(failure);
        // 同一客户端可在 DATA 后失去确认；超时/断连及部分发送一律 unknown，不自动重试。
        boolean uncertain =
                causes.stream()
                        .anyMatch(
                                cause ->
                                        cause instanceof SocketTimeoutException
                                                || cause instanceof EOFException
                                                || (cause instanceof SSLException
                                                        && !(cause
                                                                instanceof SSLHandshakeException))
                                                || (cause instanceof SocketException
                                                        && !(cause instanceof ConnectException)
                                                        && !(cause
                                                                instanceof NoRouteToHostException))
                                                || (cause instanceof SendFailedException send
                                                        && send.getValidSentAddresses() != null
                                                        && send.getValidSentAddresses().length
                                                                > 0));
        if (uncertain)
            return report(
                    causes.stream().anyMatch(SocketTimeoutException.class::isInstance)
                            ? "TIMEOUT"
                            : causes.stream().anyMatch(SSLException.class::isInstance)
                                    ? "TLS"
                                    : "UNKNOWN",
                    false,
                    causes,
                    true);
        for (Throwable cause : causes) {
            if (cause instanceof MailAuthenticationException
                    || cause instanceof AuthenticationFailedException)
                return report("AUTHENTICATION", false, causes);
            if (cause instanceof SSLException) return report("TLS", false, causes);
            if (cause instanceof MailParseException || cause instanceof MailPreparationException)
                return report("CLIENT_CONFIGURATION", false, causes);
        }
        for (Throwable cause : causes) {
            if (cause instanceof UnknownHostException) return report("DNS", true, causes);
            if (cause instanceof ConnectException || cause instanceof NoRouteToHostException)
                return report("CONNECTION", true, causes);
            if (cause instanceof SMTPAddressFailedException)
                return report("DELIVERY", false, causes);
            if (cause instanceof SMTPSendFailedException smtp
                    && smtp.getReturnCode() >= 400
                    && smtp.getReturnCode() <= 599) return report("DELIVERY", false, causes);
        }
        return report("UNKNOWN", false, causes, true);
    }

    private MailDeliveryResult report(String reason, boolean retryable, List<Throwable> causes) {
        return report(reason, retryable, causes, false);
    }

    private MailDeliveryResult report(
            String reason, boolean retryable, List<Throwable> causes, boolean uncertain) {
        String protocol = "OTHER";
        int port = -1;
        if (sender instanceof JavaMailSenderImpl implementation) {
            String configured = implementation.getProtocol();
            if ("smtp".equals(configured) || "smtps".equals(configured)) protocol = configured;
            port = implementation.getPort();
        }
        log.warn(
                "SMTP mail failed: reason={} exceptionClasses={} protocol={} port={}",
                reason,
                causes.stream().map(cause -> cause.getClass().getSimpleName()).distinct().toList(),
                protocol,
                port);
        return new MailDeliveryResult(uncertain ? "unknown" : "failed", reason, retryable);
    }

    private static MailDeliveryResult failed(String reason, boolean retryable) {
        return new MailDeliveryResult("failed", reason, retryable);
    }

    private static boolean validMailbox(String from) {
        if (from == null || from.isBlank() || from.length() > 254) return false;
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
}
