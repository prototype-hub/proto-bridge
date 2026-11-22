package com.gaiys.proto.prototypehub.service.notification;

import com.gaiys.proto.notification.NotificationMailTransport;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 基础邮箱 OTP 的固定用途适配器；共用 SMTP 传输但保持原有错误码和业务权益独立性。 */
@Component
public class SmtpNotificationGateway implements NotificationGateway {
    private final NotificationMailTransport transport;

    public SmtpNotificationGateway(NotificationMailTransport transport) {
        this.transport = transport;
    }

    @Override
    public boolean available() {
        return transport.available();
    }

    @Override
    public Mono<Void> sendVerification(String email, String code) {
        return transport
                .sendVerification(email, code)
                .flatMap(
                        result ->
                                "accepted".equals(result.outcome())
                                        ? Mono.empty()
                                        : Mono.error(unavailable()));
    }

    public static PrototypeHubException unavailable() {
        return new PrototypeHubException(
                "error.prototype-hub.review_mail_unavailable",
                "PROTOTYPE_HUB_REVIEW_MAIL_UNAVAILABLE",
                503);
    }
}
