package com.gaiys.proto.system.security.properties;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "captcha")
public class CaptchaProperties {
    private boolean enabled;
    private Duration ttl = Duration.ofMinutes(2);
}
