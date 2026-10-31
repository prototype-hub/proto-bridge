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
 * Imports the packaged default menu tree into an empty table and adds a missing built-in recycle
 * entry to an existing product tree after EasyORM startup.
 *
 * <p>Every invocation takes the shared initialization row lock and rechecks the table in the same
 * reactive transaction as the existing tree save. Existing rows prevent full import; the targeted
 * addition preserves customized or disabled entries and never assigns role grants. Classpath I/O is
 * bounded and isolated; this service never reads the source export or local config.
 */
@Service
public class MenuStartupInitializationService {
    static final String DEFAULT_RESOURCE = "menus/default-menus.json";
    // 启动仅导入当前产品的内置资源；既有 iot 等归属由非空表保护保留，不做迁移或覆盖。
    static final String DEFAULT_OWNER = "proto";
    private static final String PROTOTYPE_PARENT = "131a644480a0c3253a08103fedfe3280";
    private static final String RECYCLE_ID = "prototype-hub-recycle";
    private static final String RECYCLE_CODE = "prototype-hub/Recycle";
    private static final String RECYCLE_URL = "/prototype-hub/recycle-bin";
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

    /** 仅补已存在产品树中缺失的回收站入口；不重写自定义菜单、禁用项或任何角色授权。 */
    public Mono<Boolean> ensureRecycleMenuIfMissing() {
        return ensureBuiltInMenuIfMissing(RECYCLE_ID, RECYCLE_CODE, RECYCLE_URL);
    }

    /** 仅补缺失统计入口，保留既有首页、自定义/禁用菜单和角色授权。 */
    public Mono<Boolean> ensureStatisticsMenuIfMissing() {
        return ensureBuiltInMenuIfMissing(
                "prototype-hub-statistics",
                "prototype-hub/Statistics",
                "/prototype-hub/statistics");
    }

    /** 仅补缺失的安装凭证入口，不改变原菜单、首页或角色授权。 */
    public Mono<Boolean> ensureLicenseMenuIfMissing() {
        return ensureBuiltInMenuIfMissing(
                "prototype-hub-license", "prototype-hub/License", "/prototype-hub/license");
    }

    private Mono<Boolean> ensureBuiltInMenuIfMissing(String id, String code, String url) {
        return Mono.defer(
                        () ->
                                initializationLock
                                        .acquire()
                                        .then(menus.findById(PROTOTYPE_PARENT))
                                        .filter(
                                                parent ->
                                                        DEFAULT_OWNER.equals(parent.getOwner())
                                                                && "prototype-hub"
                                                                        .equals(parent.getCode())
                                                                && Boolean.TRUE.equals(
                                                                        parent.getEnabled()))
                                        .flatMap(
                                                parent ->
                                                        menus.createQuery()
                                                                .where(MenuEntity::getId, id)
                                                                .or(MenuEntity::getCode, code)
                                                                .or(
                                                                        MenuEntity
                                                                                ::getComponentCode,
                                                                        code)
                                                                .or(MenuEntity::getUrl, url)
                                                                .count()
                                                                .flatMap(
                                                                        count ->
                                                                                count > 0
                                                                                        ? Mono.just(
                                                                                                false)
                                                                                        : insertBuiltInMenu(
                                                                                                id)))
                                        .defaultIfEmpty(false))
                .as(transactions::transactional);
    }

    private Mono<Boolean> insertBuiltInMenu(String id) {
        return Mono.fromCallable(() -> readMenus(new ClassPathResource(DEFAULT_RESOURCE)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(
                        rows ->
                                rows.stream()
                                        .filter(row -> PROTOTYPE_PARENT.equals(row.getId()))
                                        .flatMap(row -> row.getChildren().stream())
                                        .filter(row -> id.equals(row.getId()))
                                        .findFirst()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "Packaged built-in menu is missing")))
                // 复用官方树保存补齐path/level，并保留已有父节点的展示配置。
                .flatMap(row -> menus.save(Mono.just(row)).thenReturn(true));
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
