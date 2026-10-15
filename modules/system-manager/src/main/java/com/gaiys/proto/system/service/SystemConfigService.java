package com.gaiys.proto.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.system.configuration.properties.FrontendProperties;
import com.gaiys.proto.system.entity.SystemConfigEntity;
import com.gaiys.proto.system.service.support.ManagementSupport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 系统配置域的响应式读写服务。控制合法域与公开项，配置持久化委托配置存储服务，文件地址由当前配置解析。 */
@Service
@RequiredArgsConstructor
public class SystemConfigService {
    private final SystemConfigStoreService store;
    private final FrontendProperties defaults;
    private final ObjectMapper mapper;
    private static final int MAX_SCOPE_BATCH = 3;
    private static final Set<String> SCOPES =
            Set.of("front", "amap", "paths", "calendar-tag-color");
    private static final Set<String> FRONT =
            Set.of(
                    "title",
                    "logo",
                    "ico",
                    "background",
                    "headerTheme",
                    "showRecordNumber",
                    "recordNumber",
                    "policeRecordNumber",
                    "copyright");

    /** 配置域和域内配置项；合法配置域与敏感项边界仍由配置服务维护。 */
    @Schema(name = "SystemConfigScope", description = "系统配置域")
    public record Scope(
            @Schema(
                            description = "配置域标识：front、amap、paths 或 calendar-tag-color",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String scope,
            @Schema(
                            description = "配置项最多30个，JSON最多16KiB；calendar-tag-color为标签ID到颜色字符串的映射",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    Map<String, Object> properties) {}

    public Flux<Scope> scopes(List<String> scopes) {
        return Flux.defer(
                () -> {
                    validateBatch(scopes);
                    // 先校验整批，避免读取部分配置后才因后续空域或未知域终止响应。
                    scopes.forEach(SystemConfigService::validateReadScope);
                    return Flux.fromIterable(scopes)
                            .concatMap(
                                    scope -> read(scope).map(values -> new Scope(scope, values)));
                });
    }

    public Mono<Map<String, Object>> get(String scope) {
        return Mono.defer(
                () -> {
                    validateReadScope(scope);
                    return read(scope);
                });
    }

    private static void validateReadScope(String scope) {
        if (scope == null) throw ManagementSupport.invalid();
        if (!SCOPES.contains(scope)) throw ManagementSupport.missing();
    }

    private static void validateBatch(List<?> scopes) {
        if (scopes == null || scopes.size() > MAX_SCOPE_BATCH) throw ManagementSupport.invalid();
    }

    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> read(String scope) {
        return store.findById(scope)
                .map(SystemConfigEntity::getProperties)
                .defaultIfEmpty(Map.of())
                .map(
                        saved -> {
                            Map<String, Object> effective = new LinkedHashMap<>();
                            if ("front".equals(scope)) {
                                effective.putAll(mapper.convertValue(defaults, Map.class));
                                saved.forEach(
                                        (key, value) -> {
                                            if (FRONT.contains(key)) effective.put(key, value);
                                        });
                            } else {
                                if ("paths".equals(scope)) effective.put("base-path", "/api");
                                effective.putAll(saved);
                            }
                            return effective;
                        });
    }

    @Transactional
    public Mono<Void> save(List<Scope> scopes) {
        return Mono.defer(
                () -> {
                    validateBatch(scopes);
                    // 在调用存储之前完成所有域的校验，非法后续项不能使先前合法项部分写入。
                    List<SystemConfigEntity> entities =
                            scopes.stream().map(this::validatedEntity).toList();
                    return store.save(Flux.fromIterable(entities)).then();
                });
    }

    private SystemConfigEntity validatedEntity(Scope scope) {
        if (scope == null
                || scope.scope() == null
                || !SCOPES.contains(scope.scope())
                || scope.properties() == null
                || scope.properties().keySet().stream().anyMatch(key -> key == null)
                || scope.properties().size() > 30
                || ("front".equals(scope.scope())
                        && !FRONT.containsAll(scope.properties().keySet())))
            throw ManagementSupport.invalid();
        if ("paths".equals(scope.scope()) && scope.properties().containsKey("base-path"))
            filePrefix(scope.properties().get("base-path"));
        if ("front".equals(scope.scope()))
            scope.properties()
                    .forEach(
                            (key, value) -> {
                                if (key.equals("showRecordNumber")
                                        ? !(value instanceof Boolean)
                                        : !(value instanceof String))
                                    throw ManagementSupport.invalid();
                                if (value instanceof String text && text.length() > 4096)
                                    throw ManagementSupport.invalid();
                                if (key.equals("headerTheme")
                                        && !Set.of("light", "dark").contains(value))
                                    throw ManagementSupport.invalid();
                            });
        if ("calendar-tag-color".equals(scope.scope())
                && scope.properties().values().stream()
                        .anyMatch(value -> !(value instanceof String)))
            throw ManagementSupport.invalid();
        try {
            if (mapper.writeValueAsBytes(scope.properties()).length > 16384)
                throw ManagementSupport.invalid();
        } catch (java.io.IOException error) {
            throw ManagementSupport.invalid();
        }
        SystemConfigEntity entity = new SystemConfigEntity();
        entity.setId(scope.scope());
        entity.setProperties(new LinkedHashMap<>(scope.properties()));
        return entity;
    }

    public Mono<String> publicFileUrl(String relative) {
        return get("paths")
                .map(
                        values ->
                                filePrefix(values.get("base-path"))
                                        + (relative.startsWith("/api/")
                                                ? relative.substring(4)
                                                : relative));
    }

    private static String filePrefix(Object value) {
        if (!(value instanceof String prefix) || prefix.isBlank() || prefix.length() > 2048)
            throw ManagementSupport.invalid();
        java.net.URI uri;
        try {
            uri = java.net.URI.create(prefix);
        } catch (IllegalArgumentException error) {
            throw ManagementSupport.invalid();
        }
        if (uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getUserInfo() != null
                || (uri.isAbsolute()
                        ? !Set.of("http", "https").contains(uri.getScheme())
                                || uri.getHost() == null
                        : !prefix.startsWith("/") || prefix.startsWith("//")))
            throw ManagementSupport.invalid();
        return prefix.replaceAll("/+$", "");
    }
}
