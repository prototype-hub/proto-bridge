package com.gaiys.proto.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class SchedulingConfigurationTest {
    @Test
    void nonWebContextHasNoScheduledTaskProcessor() {
        new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfiguration.class, Fixture.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context)
                                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                            assertThat(context.getBean(Probe.class).called.getCount()).isEqualTo(1);
                        });
    }

    @Test
    void reactiveWebContextStillRunsScheduledTasks() {
        new ReactiveWebApplicationContextRunner()
                .withUserConfiguration(SchedulingConfiguration.class, Fixture.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context)
                                    .hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                            assertThat(
                                            context.getBean(Probe.class)
                                                    .called
                                                    .await(5, TimeUnit.SECONDS))
                                    .isTrue();
                        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean
        Probe scheduledProbe() {
            return new Probe();
        }
    }

    static class Probe {
        private final CountDownLatch called = new CountDownLatch(1);

        @Scheduled(fixedDelay = 10)
        public void tick() {
            called.countDown();
        }
    }
}
