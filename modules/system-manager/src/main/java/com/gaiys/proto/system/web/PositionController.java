package com.gaiys.proto.system.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.system.entity.PositionEntity;
import com.gaiys.proto.system.service.OrganizationService;
import com.gaiys.proto.system.service.PositionQueryService;
import com.gaiys.proto.system.service.PositionService;
import com.gaiys.proto.system.service.SystemManagementService;
import com.gaiys.proto.system.service.support.ManagementSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.DeleteAction;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.hswebframework.web.crud.web.reactive.ReactiveServiceCrudController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 职位管理接口。标准实体 CRUD 复用 hsweb；专用 JSON 导入支持组织内新增/更新，详情查询由关联适配服务解析。 导入角色统一为官方角色 ID，不允许跨组织覆盖已有职位。 */
@Tag(name = "职位管理")
@RestController
@RequestMapping("/position")
@Authorize
@Resource(id = "position", name = "职位管理")
@RequiredArgsConstructor
public class PositionController implements ReactiveServiceCrudController<PositionEntity, String> {
    private final PositionService service;
    private final OrganizationService organizations;
    private final SystemManagementService management;
    private final PositionQueryService queries;
    private final ObjectMapper mapper;

    @Override
    public PositionService getService() {
        return service;
    }

    private Mono<PositionEntity> validate(String orgId, PositionEntity input) {
        input.setOrgId(orgId);
        return organizations
                .findById(orgId)
                .switchIfEmpty(Mono.error(ManagementSupport.missing()))
                .thenMany(
                        Flux.fromIterable(ManagementSupport.ids(input.getRoles()))
                                .concatMap(
                                        role ->
                                                management
                                                        .dimension("role", role)
                                                        .map(
                                                                d ->
                                                                        Map.of(
                                                                                "id",
                                                                                d.getId(),
                                                                                "name",
                                                                                d.getName()))))
                .collectList()
                .map(
                        roles -> {
                            input.setRoles(roles);
                            return input;
                        });
    }

    private PositionEntity importPosition(Map<String, Object> source) {
        Map<String, Object> values = new LinkedHashMap<>(source);
        if (source.containsKey("roles")) {
            Object roleValues = source.get("roles");
            if (roleValues != null && !(roleValues instanceof Collection<?>))
                throw ManagementSupport.invalid();
            Collection<?> roles = roleValues == null ? List.of() : (Collection<?>) roleValues;
            // 现有 JSON 导出记录经前端 map(id => ({id})) 后会多包一层；仅接收这一层历史格式。
            List<Object> normalized =
                    roles.stream()
                            .map(
                                    value -> {
                                        Object id =
                                                value instanceof Map<?, ?> role
                                                        ? role.get("id")
                                                        : value;
                                        if (id instanceof Map<?, ?> nested) id = nested.get("id");
                                        if (!(id instanceof String))
                                            throw ManagementSupport.invalid();
                                        return id;
                                    })
                            .toList();
            values.put(
                    "roles",
                    ManagementSupport.ids(normalized).stream()
                            .map(id -> Map.of("id", id))
                            .toList());
        }
        try {
            return mapper.convertValue(values, PositionEntity.class);
        } catch (IllegalArgumentException error) {
            throw ManagementSupport.invalid();
        }
    }

    private Mono<PositionEntity> validateImport(String orgId, PositionEntity row) {
        if (row.getOrgId() != null && !row.getOrgId().isBlank() && !orgId.equals(row.getOrgId()))
            return Mono.error(ManagementSupport.conflict());
        if (row.getId() == null || row.getId().isBlank())
            row.setId(service.getIDGenerator().generate());
        return service.findById(row.getId())
                .flatMap(
                        existing ->
                                orgId.equals(existing.getOrgId())
                                        ? validate(orgId, row)
                                        : Mono.error(ManagementSupport.conflict()))
                .switchIfEmpty(Mono.defer(() -> validate(orgId, row)));
    }

    @Operation(summary = "在组织内创建职位")
    @PostMapping("/{orgId}/_create")
    @SaveAction
    @Transactional
    public Mono<PositionEntity> create(
            @PathVariable String orgId, @RequestBody Map<String, Object> input) {
        PositionEntity row = importPosition(input);
        if (row.getOrgId() != null && !row.getOrgId().isBlank() && !orgId.equals(row.getOrgId()))
            return Mono.error(ManagementSupport.conflict());
        return validate(orgId, row)
                .flatMap(valid -> service.insert(Mono.just(valid)).thenReturn(valid));
    }

    @Operation(summary = "在组织内批量保存职位")
    @PatchMapping("/{orgId}/_save")
    @SaveAction
    @Transactional
    public Mono<Void> saveDetails(
            @PathVariable String orgId, @RequestBody Flux<Map<String, Object>> input) {
        return input.map(this::importPosition)
                .concatMap(row -> validateImport(orgId, row))
                .as(service::save)
                .then();
    }

    @Operation(summary = "分页查询职位关联详情")
    @PostMapping("/_query/detail")
    @QueryAction
    public Mono<PagerResult<PositionEntity>> detailPage(@RequestBody QueryParamEntity query) {
        ManagementSupport.limit(query);
        return queries.resolve(query)
                .flatMap(service::queryPager)
                .flatMap(
                        page ->
                                queries.details(page.getData())
                                        .map(rows -> PagerResult.of(page.getTotal(), rows, query)));
    }

    @Operation(summary = "查询全部职位关联详情")
    @PostMapping({"/_query/detail/no-paging", "/_query/all"})
    @QueryAction
    public Mono<List<PositionEntity>> detailAll(@RequestBody QueryParamEntity query) {
        return queries.resolve(query.noPaging())
                .flatMapMany(service::query)
                .collectList()
                .flatMap(queries::details);
    }

    @Operation(summary = "分页查询职位")
    @Override
    @GetMapping("/_query")
    @QueryAction
    public Mono<PagerResult<PositionEntity>> queryPager(QueryParamEntity query) {
        return detailPage(query);
    }

    @Operation(summary = "查询职位列表")
    @Override
    @GetMapping("/_query/no-paging")
    @QueryAction
    public Flux<PositionEntity> query(QueryParamEntity query) {
        return queries.resolve(query)
                .flatMapMany(service::query)
                .collectList()
                .flatMap(queries::details)
                .flatMapMany(Flux::fromIterable);
    }

    @Operation(summary = "删除职位")
    @Override
    @DeleteMapping("/{id:.+}")
    @DeleteAction
    public Mono<PositionEntity> delete(@PathVariable String id) {
        return service.createQuery()
                .where(PositionEntity::getParentId, id)
                .count()
                .flatMap(
                        count -> {
                            if (count > 0) return Mono.error(ManagementSupport.conflict());
                            return service.findById(id)
                                    .flatMap(row -> service.deleteById(id).thenReturn(row));
                        });
    }

    @Operation(summary = "读取职位关联详情")
    @Override
    @GetMapping("/{id:.+}")
    @QueryAction
    public reactor.core.publisher.Mono<PositionEntity> getById(@PathVariable String id) {
        return service.findById(id)
                .flatMap(row -> queries.details(List.of(row)).map(rows -> rows.get(0)))
                .switchIfEmpty(
                        reactor.core.publisher.Mono.error(
                                com.gaiys.proto.system.service.support.ManagementSupport
                                        .missing()));
    }
}
