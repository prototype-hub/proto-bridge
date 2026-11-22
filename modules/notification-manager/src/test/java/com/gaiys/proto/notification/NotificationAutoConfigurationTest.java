package com.gaiys.proto.notification;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.test.StepVerifier;

class NotificationAutoConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    MailSenderAutoConfiguration.class,
                                    NotificationAutoConfiguration.class))
                    .withBean(ObservationRegistry.class, () -> ObservationRegistry.NOOP);

    @Test
    void missingMailAndBusinessPolicyStillAssembleBasicTransportWithoutConnecting() {
        context.run(
                application -> {
                    assertNull(application.getStartupFailure());
                    var transport = application.getBean(NotificationMailTransport.class);
                    assertEquals(
                            1, application.getBeansOfType(NotificationMailTransport.class).size());
                    assertEquals(1, application.getBeansOfType(SmtpWork.class).size());
                    assertFalse(transport.available());
                    StepVerifier.create(
                                    transport.sendVerification(
                                            "recipient@example.invalid", "123456"))
                            .expectNext(new MailDeliveryResult("failed", "NOT_CONFIGURED", false))
                            .verifyComplete();
                    StepVerifier.create(
                                    transport.sendBusiness(
                                            "recipient@example.invalid", "Notice", "Body"))
                            .expectNext(
                                    new MailDeliveryResult("failed", "BUSINESS_NOT_ALLOWED", false))
                            .verifyComplete();
                });
    }

    @Test
    void standardMailSenderIsResolvedAfterBootConfigurationWithoutOpeningConnection() {
        context.withPropertyValues("spring.mail.host=127.0.0.1", "spring.mail.port=1")
                .run(
                        application -> {
                            assertNull(application.getStartupFailure());
                            assertTrue(
                                    application
                                            .getBean(NotificationMailTransport.class)
                                            .available());
                            assertEquals(
                                    1,
                                    application
                                            .getBeansOfType(NotificationMailTransport.class)
                                            .size());
                        });
    }
}
