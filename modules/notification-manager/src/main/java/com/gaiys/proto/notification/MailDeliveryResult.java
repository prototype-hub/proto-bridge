package com.gaiys.proto.notification;

/**
 * SMTP 的安全结果快照，禁止携带地址、正文、验证码、原始异常或异常 cause。
 *
 * @param outcome 固定为 accepted、failed 或 unknown；accepted 不等于到达收件箱
 * @param reason 固定诊断类别，供调用方保存和翻译
 * @param knownPreSendRetryable 仅在能够证明未开始发送的瞬态失败时为 true
 * @since 1.0.0
 * @see NotificationMailTransport
 */
public record MailDeliveryResult(String outcome, String reason, boolean knownPreSendRetryable) {}
