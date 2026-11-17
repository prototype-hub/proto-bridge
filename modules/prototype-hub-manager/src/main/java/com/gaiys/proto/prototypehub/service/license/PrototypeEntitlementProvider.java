package com.gaiys.proto.prototypehub.service.license;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.AdvancedEntitlements;
import reactor.core.publisher.Mono;

/**
 * 基础版公共凭证读取契约；供已授权模块读取已验声明、免费权益和当前实际容量快照。 不缓存状态；每次订阅从持久记录与当前信任配置重新验证，纯策略消费由独立模块提供。
 * 商业策略由可选模块提供；Web调用方必须自行校验安装级或具体业务权限。
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

    /**
     * 每次订阅重新读取凭证和策略，不写入、缓存或远程调用；数据库或配置异常直接传播。
     *
     * @return 非空的当前实际高级权益；缺模块或失效不是可用授权
     * @since 1.0.0
     */
    default Mono<AdvancedEntitlements> currentAdvanced() {
        return current().map(PrototypeLicenseStatus::advancedEntitlements);
    }

    /**
     * 在业务准入或重试点按明确功能ID重验；不替调用者检查账号、动作或项目范围。 不产生激活副作用，禁止把该检查替换成历史快照或容量判断。
     *
     * @param featureId 业务模块声明的功能ID，必须与签名中的ID精确匹配
     * @return 获授时完成；到期或未授时为403业务异常，底层读取异常不降级为允许
     * @since 1.0.0
     */
    default Mono<Void> requireFeature(String featureId) {
        return currentAdvanced()
                .flatMap(
                        value -> {
                            if (value.moduleAvailable()
                                    && java.util.Set.of("permanent", "trial")
                                            .contains(value.status())
                                    && value.features().contains(featureId)) return Mono.empty();
                            boolean expired = "expired".equals(value.status());
                            return Mono.error(
                                    PrototypeHubException.forbidden(
                                            expired
                                                    ? "error.prototype-hub.advanced_trial_expired"
                                                    : "error.prototype-hub.advanced_feature_unavailable",
                                            expired
                                                    ? "PROTOTYPE_HUB_ADVANCED_TRIAL_EXPIRED"
                                                    : "PROTOTYPE_HUB_ADVANCED_FEATURE_UNAVAILABLE"));
                        });
    }
}
