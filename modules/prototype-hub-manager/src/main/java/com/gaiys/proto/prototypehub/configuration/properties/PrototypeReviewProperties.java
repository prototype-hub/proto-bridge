package com.gaiys.proto.prototypehub.configuration.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 邮箱身份仅在分享显式开启后使用；默认要求 HTTPS Cookie，不扩大通用 CORS 或转发头信任。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "prototype-hub.review")
public class PrototypeReviewProperties {
    private boolean cookieSecure = true;
    private String trustedOrigin;
}
