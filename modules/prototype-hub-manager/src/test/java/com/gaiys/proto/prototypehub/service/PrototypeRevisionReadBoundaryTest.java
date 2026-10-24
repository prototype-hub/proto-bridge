package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.storage.scanner.PrototypePackageQualityScanner;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.response.RevisionQualityResponse;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class PrototypeRevisionReadBoundaryTest {
    @Test
    void qualityWaitsForAsynchronousConnectionReleaseBeforeReadingFiles() {
        var query = mock(ReactiveQuery.class, RETURNS_SELF);
        var repository = mock(ReactiveRepository.class);
        when(repository.createQuery()).thenReturn(query);
        var revision = new PrototypeRevisionEntity();
        revision.setId("revision");
        var version = new PrototypeVersionEntity();
        version.setId("version");
        version.setProjectId("project");
        version.setCurrentRevisionId("revision");
        Sinks.One<Void> release = Sinks.one();
        AtomicBoolean releasing = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        Mono<Void> cleanup = release.asMono().doOnSuccess(ignored -> released.set(true));
        // 使用 hsweb 的 usingWhen 连接契约；异步归还受独立门控制，不借助延时断言。
        Flux<PrototypeRevisionEntity> rows =
                Flux.usingWhen(
                        Mono.just("connection"),
                        ignored -> Flux.just(revision),
                        ignored -> {
                            releasing.set(true);
                            return cleanup;
                        },
                        (ignored, error) -> cleanup,
                        ignored -> cleanup);
        when(query.fetch()).thenReturn(rows);
        // 真实 EasyORM fetchOne 的 take(1) 会在异步取消清理前把 row 发给下游。
        when(query.fetchOne()).thenAnswer(ignored -> rows.take(1).singleOrEmpty());
        var scanner = mock(PrototypePackageQualityScanner.class);
        var response =
                new RevisionQualityResponse(
                        "version", "revision", "hash", "index.html", null, null, true, List.of());
        when(scanner.scan(revision))
                .thenAnswer(
                        ignored -> {
                            assertTrue(released.get(), "文件读取必须在数据库连接归还后开始");
                            return response;
                        });
        try (var work = new FileWork()) {
            var service =
                    new PrototypeRevisionService(work, ObservationRegistry.NOOP, scanner) {
                        @Override
                        public ReactiveRepository<PrototypeRevisionEntity, String> getRepository() {
                            return repository;
                        }
                    };
            StepVerifier.create(service.quality(version, "revision"))
                    .then(
                            () -> {
                                assertTrue(releasing.get());
                                assertFalse(released.get());
                                verifyNoInteractions(scanner);
                                release.tryEmitEmpty();
                            })
                    .expectNext(response)
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
            verify(scanner).scan(revision);
        }
    }
}
