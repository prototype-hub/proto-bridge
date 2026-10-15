package com.gaiys.proto.system.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.system.configuration.properties.FrontendProperties;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** 验证配置参数在响应式存储订阅之前整批拒绝，不能依赖事务回滚掩盖部分写入。 */
class SystemConfigServiceBoundaryTest {
    final SystemConfigStoreService store = mock(SystemConfigStoreService.class);
    final SystemConfigService service =
            new SystemConfigService(store, new FrontendProperties(), new ObjectMapper());

    void rejects(Publisher<?> result, int status) {
        StepVerifier.create(result)
                .expectErrorSatisfies(
                        failure -> {
                            var error = assertInstanceOf(BusinessException.class, failure);
                            assertEquals(status, error.getStatus());
                            assertEquals(
                                    status == 400 ? "BAD_REQUEST" : "NOT_FOUND", error.getCode());
                        })
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void nullInputAndLaterNullEntriesNeverReachStorage() {
        rejects(service.save(null), 400);
        var first = new SystemConfigService.Scope("front", Map.of("title", "Must not save"));
        for (SystemConfigService.Scope invalid :
                Arrays.asList(
                        null,
                        new SystemConfigService.Scope(null, Map.of()),
                        new SystemConfigService.Scope("front", null))) {
            rejects(service.save(Arrays.asList(first, invalid)), 400);
        }
        verifyNoInteractions(store);
    }

    @Test
    void validatesEveryScopeBeforeInvokingTheStore() {
        var first = new SystemConfigService.Scope("amap", Map.of("key", "Must not save"));
        var tooManyColors = new LinkedHashMap<String, Object>();
        for (int index = 0; index < 31; index++) tooManyColors.put("tag-" + index, "#1677ff");
        for (SystemConfigService.Scope invalid :
                List.of(
                        new SystemConfigService.Scope("unknown", Map.of()),
                        new SystemConfigService.Scope("front", Map.of("password", "private")),
                        new SystemConfigService.Scope("front", Map.of("showRecordNumber", "true")),
                        new SystemConfigService.Scope("front", Map.of("headerTheme", "other")),
                        new SystemConfigService.Scope("front", Map.of("title", "a".repeat(4097))),
                        new SystemConfigService.Scope(
                                "paths", Map.of("base-path", "http://192.168.0.280:5173/api")),
                        new SystemConfigService.Scope("calendar-tag-color", Map.of("holiday", 123)),
                        new SystemConfigService.Scope("calendar-tag-color", tooManyColors),
                        new SystemConfigService.Scope(
                                "calendar-tag-color", Map.of("holiday", "a".repeat(16385))))) {
            rejects(service.save(List.of(first, invalid)), 400);
        }
        rejects(service.save(List.of(first, first, first, first)), 400);
        verifyNoInteractions(store);
    }

    @Test
    void invalidReadBatchCannotProducePartialReads() {
        rejects(service.scopes(null), 400);
        rejects(service.scopes(Arrays.asList("front", null)), 400);
        rejects(service.scopes(List.of("front", "unknown")), 404);
        rejects(service.scopes(List.of("front", "amap", "paths", "calendar-tag-color")), 400);
        rejects(service.get(null), 400);
        rejects(service.get("unknown"), 404);
        verifyNoInteractions(store);
    }

    @Test
    void newlySupportedColorScopeUsesTheExistingEmptyConfigContract() {
        when(store.findById("calendar-tag-color")).thenReturn(Mono.empty());
        StepVerifier.create(service.get("calendar-tag-color"))
                .expectNext(Map.of())
                .verifyComplete();
    }
}
