package com.gaiys.proto.notification;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SmtpWorkTest {
    @Test
    void monitorFollowsOwnedLifecycleAndExposesOnlyBoundedPoolCounters() throws Exception {
        var server = ManagementFactory.getPlatformMBeanServer();
        var name = new ObjectName("org.jetlinks:type=NotificationSmtp,name=shared");
        try (var work = new SmtpWork();
                var other = new SmtpWork()) {
            work.registerMonitor();
            other.registerMonitor();
            assertTrue(server.isRegistered(name));
            var active = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var task =
                    work.call(
                                    () -> {
                                        active.countDown();
                                        assertTrue(release.await(3, TimeUnit.SECONDS));
                                        return "completed";
                                    })
                            .cache();
            StepVerifier.create(task)
                    .then(
                            () -> {
                                assertDoesNotThrow(
                                        () -> assertTrue(active.await(3, TimeUnit.SECONDS)));
                                assertDoesNotThrow(
                                        () -> assertEquals(1, server.getAttribute(name, "Active")));
                                release.countDown();
                            })
                    .expectNext("completed")
                    .verifyComplete();
            other.close();
            assertTrue(server.isRegistered(name));
            var names =
                    java.util.Arrays.stream(server.getMBeanInfo(name).getAttributes())
                            .map(attribute -> attribute.getName())
                            .sorted()
                            .toList();
            assertEquals(java.util.List.of("Active", "Completed", "Queued", "Rejected"), names);
        }
        assertFalse(server.isRegistered(name));
    }

    @Test
    void poolRejectsBeyondFourRunningAndSixtyFourQueuedWithoutRunningRejectedOperation() {
        try (var work = new SmtpWork()) {
            var running = new CountDownLatch(4);
            var release = new CountDownLatch(1);
            var subscriptions = new java.util.ArrayList<reactor.core.Disposable>();
            var tasks = new java.util.ArrayList<Mono<Boolean>>();
            try {
                for (int i = 0; i < 4; i++) {
                    var task =
                            work.call(
                                            () -> {
                                                running.countDown();
                                                return release.await(5, TimeUnit.SECONDS);
                                            })
                                    .cache();
                    tasks.add(task);
                    subscriptions.add(task.subscribe());
                }
                assertDoesNotThrow(() -> assertTrue(running.await(3, TimeUnit.SECONDS)));
                for (int i = 0; i < 64; i++) {
                    var task = work.call(() -> true).cache();
                    tasks.add(task);
                    subscriptions.add(task.subscribe());
                }
                StepVerifier.create(
                                work.call(
                                        () -> {
                                            fail("Rejected operation must not execute");
                                            return true;
                                        }))
                        .expectError(java.util.concurrent.RejectedExecutionException.class)
                        .verify(Duration.ofSeconds(3));
                release.countDown();
                StepVerifier.create(Flux.merge(tasks)).expectNextCount(68).verifyComplete();
            } finally {
                release.countDown();
                subscriptions.forEach(reactor.core.Disposable::dispose);
            }
        }
    }
}
