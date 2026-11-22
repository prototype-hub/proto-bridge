package com.gaiys.proto.notification;

import reactor.core.publisher.Mono;

/**
 * 同一 SMTP 客户端的用途边界；只负责一次协议调用，不持久化、重试或认定邮件已经到达收件箱。
 *
 * @since 1.0.0
 * @see BusinessMailPolicy
 * @see MailDeliveryResult
 */
public interface NotificationMailTransport {
    /** 主机和标准客户端已配置；不建立连接，也不代表收件人或服务商允许本次发送。 */
    boolean available();

    /**
     * 仅发送固定主题/正文的邮箱所有权验证码，不经过高级业务权益。
     *
     * @param email 单一完整邮箱，无显示名称或地址列表
     * @param code 六位 ASCII 数字，不进入结果、日志或追踪
     * @return 一次调用的安全结果；非 accepted 不能用于确认邮箱所有权
     */
    Mono<MailDeliveryResult> sendVerification(String email, String code);

    /**
     * 每次订阅先经业务策略授权，再使用同一客户端发送单收件人的纯文本业务通知。
     *
     * @param recipient 单一完整邮箱
     * @param subject 最多 200 字符的非空主题，不允许换行或头部覆盖
     * @param body 最多 8192 字符的非空纯文本正文
     * @return accepted 仅指服务商受理；unknown 禁止自动重发，授权缺失或失败时不发送
     */
    Mono<MailDeliveryResult> sendBusiness(String recipient, String subject, String body);
}
