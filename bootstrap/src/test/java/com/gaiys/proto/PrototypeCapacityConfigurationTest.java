package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeCapacityProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;

/** 使用真实 Boot 属性绑定验证容量配置，非法输入必须在启动阶段失败。 */
class PrototypeCapacityConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(CapacityConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PrototypeCapacityProperties.class)
    static class CapacityConfiguration {}

    @Test
    void absentValuesKeepIndependentDefaults() {
        runner.run(
                context -> {
                    assertNull(context.getStartupFailure());
                    var capacity = context.getBean(PrototypeCapacityProperties.class);
                    assertEquals(5, capacity.getProjectLimit());
                    assertEquals(5, capacity.getVersionLimit());
                });
        runner.withPropertyValues("prototype-hub.capacity.project-limit=-1")
                .run(
                        context ->
                                assertEquals(
                                        5,
                                        context.getBean(PrototypeCapacityProperties.class)
                                                .getVersionLimit()));
        runner.withPropertyValues("prototype-hub.capacity.version-limit=-1")
                .run(
                        context ->
                                assertEquals(
                                        5,
                                        context.getBean(PrototypeCapacityProperties.class)
                                                .getProjectLimit()));
    }

    @ParameterizedTest
    @CsvSource({"5,5", "-1,5", "5,-1", "-1,-1", "2,3", "2147483647,1"})
    void validDimensionsBindWithoutChangingEachOther(int projects, int versions) {
        runner.withPropertyValues(
                        "prototype-hub.capacity.project-limit=" + projects,
                        "prototype-hub.capacity.version-limit=" + versions)
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var capacity = context.getBean(PrototypeCapacityProperties.class);
                            assertEquals(projects, capacity.getProjectLimit());
                            assertEquals(versions, capacity.getVersionLimit());
                        });
    }

    @Test
    void yamlIntegersBindAndNumericFractionsFailWithoutTruncation() {
        for (String values :
                new String[] {
                    "project-limit: 2\n    version-limit: -1",
                    "project-limit: -1\n    version-limit: 3"
                }) {
            yaml(values)
                    .run(
                            context -> {
                                assertNull(context.getStartupFailure());
                                var capacity = context.getBean(PrototypeCapacityProperties.class);
                                assertEquals(
                                        values.startsWith("project-limit: 2") ? 2 : -1,
                                        capacity.getProjectLimit());
                                assertEquals(
                                        values.startsWith("project-limit: 2") ? -1 : 3,
                                        capacity.getVersionLimit());
                            });
        }
        for (String dimension : new String[] {"project-limit", "version-limit"}) {
            yaml(dimension + ": 1.5")
                    .run(context -> assertNotNull(context.getStartupFailure(), dimension));
            for (Number value : new Number[] {1.5d, new BigDecimal("1.5"), 2147483648L}) {
                runner.withInitializer(
                                context ->
                                        context.getEnvironment()
                                                .getPropertySources()
                                                .addFirst(
                                                        new MapPropertySource(
                                                                "numeric-capacity",
                                                                Map.of(
                                                                        "prototype-hub.capacity."
                                                                                + dimension,
                                                                        value))))
                        .run(
                                context ->
                                        assertNotNull(
                                                context.getStartupFailure(),
                                                dimension + "=" + value));
            }
        }
    }

    private ApplicationContextRunner yaml(String values) {
        return runner.withInitializer(
                context -> {
                    try {
                        for (var source :
                                new YamlPropertySourceLoader()
                                        .load(
                                                "yaml-capacity",
                                                new ByteArrayResource(
                                                        ("prototype-hub:\n  capacity:\n    "
                                                                        + values
                                                                        + "\n")
                                                                .getBytes(StandardCharsets.UTF_8))))
                            context.getEnvironment().getPropertySources().addFirst(source);
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0",
                "-2",
                "-2147483648",
                "2147483648",
                "-2147483649",
                "1.5",
                "NaN",
                "many",
                ""
            })
    void invalidValueFailsStartupForEitherDimension(String value) {
        for (String dimension : new String[] {"project-limit", "version-limit"}) {
            runner.withPropertyValues("prototype-hub.capacity." + dimension + "=" + value)
                    .run(
                            context ->
                                    assertNotNull(
                                            context.getStartupFailure(), dimension + "=" + value));
        }
    }
}
