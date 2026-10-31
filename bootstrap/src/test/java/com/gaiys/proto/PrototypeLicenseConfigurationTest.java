package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeLicenseProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 真实Boot绑定公开信任根；空配置保持关闭，错误密钥使启动明确失败。 */
class PrototypeLicenseConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TrustConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PrototypeLicenseProperties.class)
    static class TrustConfiguration {}

    @Test
    void emptyTrustFailsClosed() {
        runner.run(
                c -> {
                    assertNull(c.getStartupFailure());
                    assertFalse(c.getBean(PrototypeLicenseProperties.class).isTrustConfigured());
                });
    }

    @Test
    void validPublicOnlyX509Binds() {
        runner.withPropertyValues(
                        "prototype-hub.license.trusted-public-keys[test]="
                                + Base64.getEncoder()
                                        .encodeToString(
                                                PrototypeLicenseVerificationTest.KEY
                                                        .getPublic()
                                                        .getEncoded()))
                .run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertNotNull(c.getBean(PrototypeLicenseProperties.class).key("test"));
                        });
    }

    @Test
    void privateMalformedAndRsaKeysCannotBecomeTrustRoots() throws Exception {
        for (String value :
                new String[] {
                    "invalid",
                    Base64.getEncoder()
                            .encodeToString(
                                    PrototypeLicenseVerificationTest.KEY.getPrivate().getEncoded()),
                    Base64.getEncoder()
                            .encodeToString(
                                    java.security.KeyPairGenerator.getInstance("RSA")
                                            .generateKeyPair()
                                            .getPublic()
                                            .getEncoded())
                })
            runner.withPropertyValues("prototype-hub.license.trusted-public-keys[test]=" + value)
                    .run(c -> assertNotNull(c.getStartupFailure()));
    }
}
