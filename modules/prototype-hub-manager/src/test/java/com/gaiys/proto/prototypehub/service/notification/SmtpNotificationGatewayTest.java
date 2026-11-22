package com.gaiys.proto.prototypehub.service.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.notification.MailDeliveryResult;
import com.gaiys.proto.notification.NotificationMailTransport;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SmtpNotificationGatewayTest {
    @Test
    void sharedTransportPreservesBasicAvailabilityAndSafe503ForEveryNonAcceptedResult() {
        var transport = mock(NotificationMailTransport.class);
        var gateway = new SmtpNotificationGateway(transport);
        assertFalse(gateway.available());
        when(transport.available()).thenReturn(true);
        assertTrue(gateway.available());
        when(transport.sendVerification("recipient@example.invalid", "123456"))
                .thenReturn(Mono.just(new MailDeliveryResult("accepted", "ACCEPTED", false)));
        StepVerifier.create(gateway.sendVerification("recipient@example.invalid", "123456"))
                .verifyComplete();
        for (String state : new String[] {"failed", "unknown"}) {
            when(transport.sendVerification("recipient@example.invalid", "123456"))
                    .thenReturn(Mono.just(new MailDeliveryResult(state, "TIMEOUT", false)));
            StepVerifier.create(gateway.sendVerification("recipient@example.invalid", "123456"))
                    .expectErrorSatisfies(
                            error -> {
                                var failure = assertInstanceOf(PrototypeHubException.class, error);
                                assertEquals(503, failure.getStatus());
                                assertEquals(
                                        "PROTOTYPE_HUB_REVIEW_MAIL_UNAVAILABLE", failure.getCode());
                                assertNull(failure.getCause());
                                assertFalse(failure.toString().contains("recipient"));
                                assertFalse(failure.toString().contains("123456"));
                            })
                    .verify();
        }
        verify(transport, times(3)).sendVerification("recipient@example.invalid", "123456");
    }
}
