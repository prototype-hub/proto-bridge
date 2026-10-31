package com.gaiys.proto.prototypehub.service.license;

import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus;
import reactor.core.publisher.Mono;

/**
 * 基础版公共凭证读取契约；供已授权模块读取已验声明和免费回退，不提供商业激活、试用或容量执行。 不缓存状态；每次订阅从持久记录与当前信任配置重新验证。Web调用方必须自行校验安装级权限。
 *
 * @since 1.0.0
 * @see com.gaiys.proto.prototypehub.service.PrototypeLicenseService
 */
public interface PrototypeEntitlementProvider {
    /**
     * 读取当前安装的声明与免费权益，不写入或远程授权调用；持久库查询在响应式调用链中使用。
     *
     * @return 恰一个状态，缺证/失效作为可解释状态，数据库不可用作为错误传播；不会启用高级功能。
     * @since 1.0.0
     */
    Mono<PrototypeLicenseStatus> current();
}
