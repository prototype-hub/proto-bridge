package com.gaiys.proto.prototypehub.service.license;

import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.AdvancedEntitlements;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.TrialActivation;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.VerifiedEntitlements;

/**
 * 可选模块的高级权益纯策略；基础包只提供验签事实和持久试用收据，不实现商业解锁规则。 策略与容量独立，无缓存、I/O或常驻任务，由每次凭证读取同步调用。
 *
 * @since 1.0.0
 * @see PrototypeEntitlementProvider
 */
public interface PrototypeAdvancedFeaturePolicy {
    /**
     * 解释本次已验证声明，不得回调provider、修改参数、写库或发送消息。
     *
     * @param claims 本次验签有效的声明，缺证或失效时为null
     * @param activation 安装首次导入收据，字段可空；永不以当前时间补造首次时间
     * @param now 本次读取的服务端Unix毫秒
     * @return 非null快照；只有当前有效的明确功能ID才能进入features，异常直接传播
     * @since 1.0.0
     */
    AdvancedEntitlements resolve(VerifiedEntitlements claims, TrialActivation activation, long now);
}
