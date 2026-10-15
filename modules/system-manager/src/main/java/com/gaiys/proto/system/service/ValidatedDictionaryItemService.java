package com.gaiys.proto.system.service;

import com.gaiys.proto.system.service.support.ManagementSupport;
import java.util.Collection;
import java.util.List;
import org.hswebframework.ezorm.rdb.mapping.defaults.SaveResult;
import org.hswebframework.web.dictionary.entity.DictionaryItemEntity;
import org.hswebframework.web.dictionary.service.DefaultDictionaryItemService;
import org.reactivestreams.Publisher;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 官方字典项服务的输入边界；整批校验先于fillOrdinal，合法序号、ID与缓存行为仍由官方实现。 */
@Service
@Primary
public class ValidatedDictionaryItemService extends DefaultDictionaryItemService {
    private static void validate(DictionaryItemEntity entity, boolean creating) {
        if (entity == null) throw ManagementSupport.invalid();
        String dictId = entity.getDictId();
        if ((creating && dictId == null)
                || (dictId != null && dictId.codePointCount(0, dictId.length()) > 64))
            throw ManagementSupport.invalid();
    }

    private static Mono<List<DictionaryItemEntity>> validated(
            Publisher<DictionaryItemEntity> input) {
        return Mono.defer(
                () -> {
                    if (input == null) return Mono.error(ManagementSupport.invalid());
                    return Flux.from(input)
                            .collectList()
                            .doOnNext(rows -> rows.forEach(row -> validate(row, true)));
                });
    }

    @Override
    public Mono<SaveResult> save(Publisher<DictionaryItemEntity> input) {
        return validated(input).flatMap(rows -> super.save(Flux.fromIterable(rows)));
    }

    @Override
    public Mono<SaveResult> save(DictionaryItemEntity input) {
        return Mono.defer(
                () -> {
                    validate(input, true);
                    return save(Mono.just(input));
                });
    }

    @Override
    public Mono<SaveResult> save(Collection<DictionaryItemEntity> input) {
        return Mono.defer(
                () -> {
                    if (input == null) return Mono.error(ManagementSupport.invalid());
                    input.forEach(row -> validate(row, true));
                    return save(Flux.fromIterable(input));
                });
    }

    @Override
    public Mono<Integer> insert(Publisher<DictionaryItemEntity> input) {
        return validated(input).flatMap(rows -> super.insert(Flux.fromIterable(rows)));
    }

    @Override
    public Mono<Integer> insert(DictionaryItemEntity input) {
        return Mono.defer(
                () -> {
                    validate(input, true);
                    return insert(Mono.just(input));
                });
    }

    @Override
    public Mono<Integer> insertBatch(Publisher<? extends Collection<DictionaryItemEntity>> input) {
        return Mono.defer(
                () -> {
                    if (input == null) return Mono.error(ManagementSupport.invalid());
                    return Flux.from(input)
                            .collectList()
                            .flatMap(
                                    batches -> {
                                        batches.forEach(
                                                rows -> rows.forEach(row -> validate(row, true)));
                                        return super.insertBatch(Flux.fromIterable(batches));
                                    });
                });
    }

    @Override
    public Mono<Integer> updateById(String id, Mono<DictionaryItemEntity> input) {
        return Mono.defer(
                () -> {
                    if (input == null) return Mono.error(ManagementSupport.invalid());
                    // dictId是不可更新列，原PUT允许省略；空publisher也沿用官方结果与缓存事件。
                    return super.updateById(id, input.doOnNext(row -> validate(row, false)));
                });
    }

    @Override
    public Mono<Integer> updateById(String id, DictionaryItemEntity input) {
        return Mono.defer(
                () -> {
                    validate(input, false);
                    return updateById(id, Mono.just(input));
                });
    }
}
