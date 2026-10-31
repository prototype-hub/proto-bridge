package com.gaiys.proto.prototypehub.service.license;

import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.EnforcedCapacity;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.VerifiedEntitlements;

/**
 * 当前已验声明到实际容量的可选纯策略。基础版无策略时始终采用免费5/5；实现由Spring唯一注册。 不负责验签、激活、权限、计数或持久化，不得读取部署扩容配置或缓存凭证。
 *
 * @since 1.0.0
 * @see PrototypeEntitlementProvider
 */
public interface PrototypeCapacityPolicy {
    /**
     * 在每次状态读取完成验签后同步调用，可能位于响应式事务及安装锁内。
     *
     * @param claims 当前有效且不可修改的已验声明；缺证或失效时为null
     * @return 非null的有限实际容量，缺证必须回免费5/5；异常传播，不重试或降级为无限
     * @implSpec 线程安全、无I/O/写入/远程调用和副作用，不得执行功能激活或修改入参
     * @since 1.0.0
     */
    EnforcedCapacity resolve(VerifiedEntitlements claims);
}
