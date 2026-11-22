package com.gaiys.proto.notification;

import reactor.core.publisher.Mono;

/**
 * 可选业务模块提供的邮件用途授权；没有实现时默认拒绝业务发送，基础验证码保持独立。
 *
 * @since 1.0.0
 * @see NotificationMailTransport#sendBusiness(String, String, String)
 */
public interface BusinessMailPolicy {
    /**
     * 每次业务发送前重新读取当前权益；成功完成表示允许，异常表示拒绝且不得产生外部 I/O。 不应缓存许可、持有数据库事务或依赖 SMTP 发送结果。
     *
     * @return 当前发送用途的授权结果；调用方另行负责账号、数据权限和收件范围校验
     */
    Mono<Void> authorize();
}
