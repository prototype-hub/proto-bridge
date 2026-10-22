package com.gaiys.proto.system.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.system.entity.MenuEntity;
import java.io.IOException;
import java.util.List;
import org.hswebframework.web.api.crud.entity.TransactionManagers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Imports the packaged default menu tree only when the entire menu table is empty after EasyORM
 * startup.
 *
 * <p>Every invocation takes the shared initialization row lock and rechecks the table in the same
 * reactive transaction as the existing tree save. Any existing row, including disabled menus and
 * previously initialized menus, prevents import. Classpath I/O is bounded and isolated; this
 * service never reads the source export or local config.
 */
@Service
public class MenuStartupInitializationService {
    static final String DEFAULT_RESOURCE = "menus/default-menus.json";
    // 启动仅导入当前产品的内置资源；既有 iot 等归属由非空表保护保留，不做迁移或覆盖。
    static final String DEFAULT_OWNER = "proto";
    private static final int MAX_RESOURCE_BYTES = 1024 * 1024;
    private final SystemInitializationLockService initializationLock;
    private final MenuService menus;
    private final MenuInitializationService initialization;
    private final ObjectMapper mapper;
    private final TransactionalOperator transactions;

    public MenuStartupInitializationService(
            SystemInitializationLockService initializationLock,
            MenuService menus,
            MenuInitializationService initialization,
            ObjectMapper mapper,
            @Qualifier(TransactionManagers.reactiveTransactionManager)
                    ReactiveTransactionManager manager) {
        this.initializationLock = initializationLock;
        this.menus = menus;
        this.initialization = initialization;
        this.mapper = mapper;
        this.transactions = TransactionalOperator.create(manager);
    }

    public Mono<Boolean> initializeIfEmpty() {
        return initializeIfEmpty(new ClassPathResource(DEFAULT_RESOURCE));
    }

    // Package-private resource seam keeps failure tests independent of the production classpath.
    Mono<Boolean> initializeIfEmpty(Resource resource) {
        return Mono.defer(
                        () ->
                                initializationLock
                                        .acquire()
                                        .then(menus.createQuery().count())
                                        .flatMap(
                                                count ->
                                                        count == 0
                                                                ? importMenus(resource)
                                                                : Mono.just(false)))
                // The nested replace() uses REQUIRED and joins this connection. Every application
                // startup follows the same row-lock protocol through commit/rollback.
                .as(transactions::transactional);
    }

    private Mono<Boolean> importMenus(Resource resource) {
        return Mono.fromCallable(() -> readMenus(resource))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        rows ->
                                Mono.defer(() -> initialization.replace(DEFAULT_OWNER, rows))
                                        .thenReturn(true));
    }

    private List<MenuEntity> readMenus(Resource resource) throws IOException {
        // The resource is only opened after the locked empty-table check. Its byte limit also
        // bounds parsing while the transaction holds the lock; tree limits belong to replace().
        try (var stream = resource.getInputStream()) {
            byte[] bytes = stream.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES)
                throw new IllegalStateException("Default menu resource exceeds the 1 MiB limit");
            List<MenuEntity> rows =
                    mapper.readerFor(new TypeReference<List<MenuEntity>>() {})
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .without(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                            .readValue(bytes);
            if (rows == null || rows.isEmpty())
                throw new IllegalStateException("Default menu resource must contain a menu array");
            return rows;
        }
    }
}
