package com.gaiys.proto.prototypehub.service.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;
import reactor.test.StepVerifier;

class SmtpNotificationGatewayTest {
    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        var p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(sender);
        return p;
    }

    @Test
    void missingHostDoesNotRequireSenderAndFailsIdentityClosed() {
        var provider = provider(null);
        try (var work = new FileWork()) {
            var gateway =
                    new SmtpNotificationGateway(
                            provider, new MockEnvironment(), work, ObservationRegistry.NOOP);
            assertFalse(gateway.available());
            verifyNoInteractions(provider);
            StepVerifier.create(gateway.sendVerification("x@example.invalid", "123456"))
                    .expectErrorSatisfies(
                            e -> assertEquals(503, ((PrototypeHubException) e).getStatus()))
                    .verify();
        }
    }

    @Test
    void bothSmtpProtocolsAlwaysHaveFiniteSafeTimeouts() {
        var sender = new JavaMailSenderImpl();
        var p = sender.getJavaMailProperties();
        p.setProperty("mail.smtp.connectiontimeout", "0");
        p.setProperty("mail.smtp.timeout", "-1");
        p.setProperty("mail.smtp.writetimeout", "garbage");
        p.setProperty("mail.smtps.timeout", "1500");
        p.setProperty("mail.smtps.connectiontimeout", "2147483648");
        try (var work = new FileWork()) {
            new SmtpNotificationGateway(
                    provider(sender),
                    new MockEnvironment().withProperty("spring.mail.host", "localhost"),
                    work,
                    ObservationRegistry.NOOP);
            for (String proto : new String[] {"smtp", "smtps"}) {
                for (String timeout : new String[] {"connectiontimeout", "timeout", "writetimeout"})
                    assertTrue(
                            Integer.parseInt(p.getProperty("mail." + proto + "." + timeout)) > 0);
                assertEquals("true", p.getProperty("mail." + proto + ".ssl.checkserveridentity"));
            }
            assertEquals("1500", p.getProperty("mail.smtps.timeout"));
        }
    }

    @Test
    void defaultEhloBypassesLocalReverseDnsAndPreservesExplicitNames() throws Exception {
        for (String configured : new String[] {"", "mail.platform.example"}) {
            var sender = new JavaMailSenderImpl();
            var properties = sender.getJavaMailProperties();
            if (!configured.isEmpty())
                for (String protocol : new String[] {"smtp", "smtps"})
                    properties.setProperty("mail." + protocol + ".localhost", configured);
            try (var work = new FileWork()) {
                new SmtpNotificationGateway(
                        provider(sender),
                        new MockEnvironment().withProperty("spring.mail.host", "localhost"),
                        work,
                        ObservationRegistry.NOOP);
                for (String protocol : new String[] {"smtp", "smtps"}) {
                    // 真实Angus解析EHLO名称，不连接服务器或调用本机hostname解析。
                    var transport =
                            (org.eclipse.angus.mail.smtp.SMTPTransport)
                                    jakarta.mail.Session.getInstance(properties)
                                            .getTransport(protocol);
                    assertEquals(
                            configured.isEmpty() ? "localhost" : configured,
                            transport.getLocalHost());
                }
            }
        }
    }

    @Test
    void messageIdUsesExplicitSessionIdentityWithoutLocalReverseDns() throws Exception {
        for (String configured : new String[] {"", "explicit@mailer.example.invalid"}) {
            var sender = new JavaMailSenderImpl();
            var properties = sender.getJavaMailProperties();
            if (!configured.isEmpty()) properties.setProperty("mail.from", configured);
            try (var work = new FileWork()) {
                new SmtpNotificationGateway(
                        provider(sender),
                        new MockEnvironment()
                                .withProperty("spring.mail.host", "localhost")
                                .withProperty("spring.mail.username", "sender@example.invalid"),
                        work,
                        ObservationRegistry.NOOP);
                var expected = configured.isEmpty() ? "sender@example.invalid" : configured;
                assertEquals(
                        expected,
                        jakarta.mail.internet.InternetAddress.getLocalAddress(sender.getSession())
                                .getAddress());
                var message = sender.createMimeMessage();
                message.setText("Fixture content");
                message.saveChanges();
                assertTrue(
                        message.getMessageID()
                                .endsWith(expected.substring(expected.indexOf('@')) + ">"));
            }
        }
    }

    @Test
    void typedVerificationSendsOnlyExplicitRecipientAndDoesNotRetryOrExposeProviderFailure() {
        var sender = mock(JavaMailSender.class);
        try (var work = new FileWork()) {
            var gateway =
                    new SmtpNotificationGateway(
                            provider(sender),
                            new MockEnvironment()
                                    .withProperty("spring.mail.host", "localhost")
                                    .withProperty("spring.mail.username", "sender@example.invalid"),
                            work,
                            ObservationRegistry.NOOP);
            StepVerifier.create(gateway.sendVerification("recipient@example.invalid", "123456"))
                    .verifyComplete();
            var capture = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(sender).send(capture.capture());
            assertArrayEquals(
                    new String[] {"recipient@example.invalid"}, capture.getValue().getTo());
            assertTrue(capture.getValue().getText().contains("123456"));
            assertEquals("sender@example.invalid", capture.getValue().getFrom());
            reset(sender);
            doThrow(new MailSendException("secret recipient@example.invalid 123456"))
                    .when(sender)
                    .send(any(SimpleMailMessage.class));
            StepVerifier.create(gateway.sendVerification("recipient@example.invalid", "123456"))
                    .expectErrorSatisfies(
                            e -> {
                                assertEquals(503, ((PrototypeHubException) e).getStatus());
                                assertFalse(e.toString().contains("recipient"));
                                assertFalse(e.toString().contains("123456"));
                                assertNull(e.getCause());
                            })
                    .verify();
            verify(sender, times(1)).send(any(SimpleMailMessage.class));
        }
    }
}
