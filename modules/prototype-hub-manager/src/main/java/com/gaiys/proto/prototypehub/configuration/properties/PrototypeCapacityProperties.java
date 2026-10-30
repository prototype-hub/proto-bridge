package com.gaiys.proto.prototypehub.configuration.properties;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 启动时绑定部署的两项独立容量，服务在原有事务锁内使用同一份配置判断新增和恢复。
 *
 * <p>缺省各为 5；-1 仅取消对应数量上限。正式 License 接管容量后应移除此配置扩容入口。
 */
@Getter
@Component
@ConfigurationProperties(prefix = "prototype-hub.capacity")
public class PrototypeCapacityProperties {
    private int projectLimit = 5;
    private int versionLimit = 5;

    public void setProjectLimit(String projectLimit) {
        this.projectLimit = validate(projectLimit, "project-limit");
    }

    public void setVersionLimit(String versionLimit) {
        this.versionLimit = validate(versionLimit, "version-limit");
    }

    // 先按原值解析，避免 YAML 小数被通用 Number -> Integer 转换截断后绕过校验。
    private static int validate(String value, String property) {
        int limit = Integer.parseInt(value.trim());
        if (limit == 0 || limit < -1) {
            throw new IllegalArgumentException(
                    "prototype-hub.capacity." + property + " must be -1 or a positive integer");
        }
        return limit;
    }
}
