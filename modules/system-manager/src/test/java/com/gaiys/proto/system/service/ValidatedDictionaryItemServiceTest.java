package com.gaiys.proto.system.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.dictionary.entity.DictionaryItemEntity;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ValidatedDictionaryItemServiceTest {
    @SuppressWarnings("unchecked")
    final ReactiveRepository<DictionaryItemEntity, String> repository =
            mock(ReactiveRepository.class);

    final ValidatedDictionaryItemService service =
            new ValidatedDictionaryItemService() {
                @Override
                public ReactiveRepository<DictionaryItemEntity, String> getRepository() {
                    return repository;
                }
            };

    DictionaryItemEntity item(String dictId) {
        var item = new DictionaryItemEntity();
        item.setDictId(dictId);
        return item;
    }

    void rejects(Mono<?> result) {
        StepVerifier.create(result)
                .expectErrorSatisfies(
                        error -> assertEquals(400, ((BusinessException) error).getStatus()))
                .verify();
    }

    @Test
    void missingDictionaryIdsRejectEveryCreationOverloadBeforeOfficialOrdinalOrRepositoryWork() {
        rejects(service.insert(item(null)));
        rejects(service.save(item(null)));
        rejects(service.save(List.of(item("valid"), item(null))));
        rejects(service.save(Flux.just(item("valid"), item(null))));
        rejects(service.insert(Flux.just(item("valid"), item(null))));
        rejects(service.insertBatch(Flux.just(List.of(item("valid")), List.of(item(null)))));
        verifyNoInteractions(repository);
    }

    @Test
    void theEntireBatchIsCheckedForDatabaseLengthAndNullElementsBeforeAnyWrite() {
        rejects(
                service.insertBatch(
                        Flux.just(List.of(item("valid")), List.of(item("a".repeat(65))))));
        rejects(service.save(Arrays.asList(item("valid"), null)));
        rejects(
                service.insert(
                        Flux.concat(
                                Mono.just(item("valid")),
                                Mono.error(new BusinessException("invalid", 400)))));
        verifyNoInteractions(repository);
    }

    @Test
    void putKeepsOmittedImmutableDictionaryIdsAndNativeEmptyPublisherBehavior() {
        service.eventPublisher = mock(ApplicationEventPublisher.class);
        when(repository.updateById(eq("existing"), any(Mono.class)))
                .thenAnswer(
                        invocation ->
                                invocation
                                        .<Mono<DictionaryItemEntity>>getArgument(1)
                                        .map(
                                                value -> {
                                                    assertEquals("Updated", value.getText());
                                                    return 1;
                                                })
                                        .defaultIfEmpty(0));
        var patch = item(null);
        patch.setText("Updated");
        StepVerifier.create(service.updateById("existing", patch)).expectNext(1).verifyComplete();
        StepVerifier.create(service.updateById("existing", Mono.empty()))
                .expectNext(0)
                .verifyComplete();
    }
}
