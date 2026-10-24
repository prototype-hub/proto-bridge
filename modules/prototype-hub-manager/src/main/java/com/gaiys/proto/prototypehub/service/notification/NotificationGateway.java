package com.gaiys.proto.prototypehub.service.notification;

import reactor.core.publisher.Mono;

/** 仅提供邮箱所有权验证通知；不接受任意主题、正文或业务通知用途。 */
public interface NotificationGateway {
    boolean available();

    /** 收件邮箱与验证码只进入 SMTP，不进入返回值、日志或追踪属性。 */
    Mono<Void> sendVerification(String email, String code);
}
