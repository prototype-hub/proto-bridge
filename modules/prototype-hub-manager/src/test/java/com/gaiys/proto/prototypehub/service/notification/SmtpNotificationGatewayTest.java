package com.gaiys.proto.prototypehub.service.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;
import reactor.test.StepVerifier;

class SmtpNotificationGatewayTest {
    private static final String PRIVATE_DETAIL = "credential recipient@example.invalid code=123456";

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
    void blankOptionalFromUsesUsernameAndCannotProduceAnEmptyEnvelope() {
        var sender = new JavaMailSenderImpl();
        sender.getJavaMailProperties().setProperty("mail.smtp.from", "");
        try (var work = new FileWork()) {
            new SmtpNotificationGateway(
                    provider(sender),
                    new MockEnvironment()
                            .withProperty("spring.mail.host", "localhost")
                            .withProperty("spring.mail.username", "sender@example.invalid")
                            .withProperty("spring.mail.properties.mail.smtp.from", ""),
                    work,
                    ObservationRegistry.NOOP);
            assertEquals(
                    "sender@example.invalid",
                    sender.getJavaMailProperties().getProperty("mail.smtp.from"));
            assertEquals(
                    "sender@example.invalid",
                    sender.getJavaMailProperties().getProperty("mail.from"));
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

    @Test
    void invalidExplicitFromFailsBeforeSenderWithoutSilentlyUsingValidUsername() {
        var sender = mock(JavaMailSender.class);
        var logger = (Logger) LoggerFactory.getLogger(SmtpNotificationGateway.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try (var work = new FileWork()) {
            for (String from :
                    new String[] {
                        "PrototypeHub", "Name <sender@example.invalid>",
                        "a@example.invalid,b@example.invalid",
                                "sender@example.invalid\r\nBcc:x@example.invalid"
                    }) {
                var gateway =
                        new SmtpNotificationGateway(
                                provider(sender),
                                new MockEnvironment()
                                        .withProperty("spring.mail.host", "localhost")
                                        .withProperty(
                                                "spring.mail.username", "valid@example.invalid")
                                        .withProperty(
                                                "spring.mail.properties.mail.smtp.from", from),
                                work,
                                ObservationRegistry.NOOP);
                StepVerifier.create(gateway.sendVerification("recipient@example.invalid", "123456"))
                        .expectErrorSatisfies(SmtpNotificationGatewayTest::assertPrivate503)
                        .verify();
            }
            assertEquals(4, events.list.size());
            for (var event : events.list) {
                assertTrue(event.getFormattedMessage().contains("reason=FROM_ADDRESS_INVALID"));
                assertPrivateLog(event);
            }
            verifyNoInteractions(sender);
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    static Stream<Arguments> providerFailures() throws Exception {
        var recipient = new InternetAddress("recipient@example.invalid");
        var rejectedAddress =
                new SMTPAddressFailedException(recipient, "RCPT TO", 550, PRIVATE_DETAIL);
        var rejectedMessage =
                new SMTPSendFailedException(
                        "DATA", 554, PRIVATE_DETAIL, rejectedAddress, null, null, null);
        var javaMail = new SendFailedException(PRIVATE_DETAIL);
        javaMail.setNextException(rejectedMessage);
        var cycleA = new RuntimeException(PRIVATE_DETAIL);
        var cycleB = new RuntimeException(PRIVATE_DETAIL, cycleA);
        cycleA.initCause(cycleB);
        return Stream.of(
                Arguments.of(
                        "AUTHENTICATION",
                        new MailAuthenticationException(
                                PRIVATE_DETAIL, new AuthenticationFailedException(PRIVATE_DETAIL))),
                Arguments.of(
                        "TLS",
                        new MailSendException(
                                PRIVATE_DETAIL,
                                new MessagingException(
                                        PRIVATE_DETAIL,
                                        new SSLHandshakeException(PRIVATE_DETAIL)))),
                Arguments.of(
                        "TIMEOUT",
                        new MailSendException(
                                PRIVATE_DETAIL, new SocketTimeoutException(PRIVATE_DETAIL))),
                Arguments.of(
                        "DNS",
                        new MailSendException(
                                PRIVATE_DETAIL, new UnknownHostException(PRIVATE_DETAIL))),
                Arguments.of(
                        "CONNECTION",
                        new MailSendException(
                                PRIVATE_DETAIL, new ConnectException(PRIVATE_DETAIL))),
                Arguments.of(
                        "DELIVERY",
                        new MailSendException(Map.of(new SimpleMailMessage(), javaMail))),
                Arguments.of("UNKNOWN", cycleA));
    }

    @ParameterizedTest
    @MethodSource("providerFailures")
    void failureCategoriesRetainNoPrivateCauseInLogResponseOrObservation(
            String reason, RuntimeException failure) {
        var sender = mock(JavaMailSender.class);
        doThrow(failure).when(sender).send(any(SimpleMailMessage.class));
        var logger = (Logger) LoggerFactory.getLogger(SmtpNotificationGateway.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        var observations = ObservationRegistry.create();
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        observations
                .observationConfig()
                .observationHandler(
                        new ObservationHandler<Observation.Context>() {
                            @Override
                            public boolean supportsContext(Observation.Context context) {
                                return true;
                            }

                            @Override
                            public void onStart(Observation.Context context) {
                                starts.incrementAndGet();
                            }

                            @Override
                            public void onStop(Observation.Context context) {
                                stops.incrementAndGet();
                                assertPrivate503(context.getError());
                            }
                        });
        try (var work = new FileWork()) {
            var gateway =
                    new SmtpNotificationGateway(
                            provider(sender),
                            new MockEnvironment()
                                    .withProperty("spring.mail.host", "localhost")
                                    .withProperty("spring.mail.username", "sender@example.invalid"),
                            work,
                            observations);
            StepVerifier.create(gateway.sendVerification("recipient@example.invalid", "123456"))
                    .expectErrorSatisfies(SmtpNotificationGatewayTest::assertPrivate503)
                    .verify(Duration.ofSeconds(3));
            assertEquals(1, starts.get());
            assertEquals(1, stops.get());
            assertEquals(1, events.list.size());
            var event = events.list.getFirst();
            assertTrue(event.getFormattedMessage().contains("reason=" + reason));
            if (reason.equals("DELIVERY")) {
                assertTrue(event.getFormattedMessage().contains("SMTPAddressFailedException"));
                assertTrue(event.getFormattedMessage().contains("SMTPSendFailedException"));
            }
            assertPrivateLog(event);
            verify(sender, times(1)).send(any(SimpleMailMessage.class));
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void disposedWorkerReportsOnlyFixedRejectionReasonAndDoesNotSend() {
        var sender = mock(JavaMailSender.class);
        var logger = (Logger) LoggerFactory.getLogger(SmtpNotificationGateway.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        var work = new FileWork();
        var gateway =
                new SmtpNotificationGateway(
                        provider(sender),
                        new MockEnvironment()
                                .withProperty("spring.mail.host", "localhost")
                                .withProperty("spring.mail.username", "sender@example.invalid"),
                        work,
                        ObservationRegistry.NOOP);
        work.close();
        try {
            StepVerifier.create(gateway.sendVerification("recipient@example.invalid", "123456"))
                    .expectErrorSatisfies(SmtpNotificationGatewayTest::assertPrivate503)
                    .verify();
            assertEquals(1, events.list.size());
            assertTrue(
                    events.list
                            .getFirst()
                            .getFormattedMessage()
                            .contains("reason=WORKER_REJECTED"));
            assertPrivateLog(events.list.getFirst());
            verifyNoInteractions(sender);
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    private static void assertPrivate503(Throwable error) {
        assertInstanceOf(PrototypeHubException.class, error);
        assertEquals(503, ((PrototypeHubException) error).getStatus());
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_MAIL_UNAVAILABLE", ((PrototypeHubException) error).getCode());
        assertNull(error.getCause());
        assertFalse(error.toString().contains("recipient"));
        assertFalse(error.toString().contains("123456"));
        assertFalse(error.toString().contains("credential"));
    }

    private static void assertPrivateLog(ILoggingEvent event) {
        assertNull(event.getThrowableProxy());
        assertFalse(event.getFormattedMessage().contains("@"));
        assertFalse(event.getFormattedMessage().contains("123456"));
        assertFalse(event.getFormattedMessage().contains("credential"));
    }
}
