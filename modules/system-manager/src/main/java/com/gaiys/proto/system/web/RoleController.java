package com.gaiys.proto.system.web;

import com.gaiys.proto.system.entity.RoleGroupEntity;
import com.gaiys.proto.system.service.RoleGroupService;
import com.gaiys.proto.system.service.SystemManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.DeleteAction;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
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

/** 角色管理接口。通过系统管理服务适配 hsweb 维度与用户绑定，角色分组使用本地持久化服务。 */
@Tag(name = "角色管理")
@RestController
@RequestMapping("/role")
@Authorize
@Resource(id = "role", name = "角色管理")
@RequiredArgsConstructor
public class RoleController {
    private final SystemManagementService service;
    private final RoleGroupService groups;

    @Operation(summary = "分页查询角色")
    @PostMapping({"/_query", "/_query/"})
    @QueryAction
    public Mono<PagerResult<Map<String, Object>>> page(@RequestBody QueryParamEntity query) {
        return service.roles(query);
    }

    @Operation(summary = "查询全部角色")
    @GetMapping("/_query/no-paging")
    @QueryAction
    public Flux<Map<String, Object>> all(QueryParamEntity query) {
        return service.allRoles(query);
    }

    @Operation(summary = "按条件查询全部角色")
    @PostMapping("/_query/no-paging")
    @QueryAction
    public Flux<Map<String, Object>> allPost(@RequestBody QueryParamEntity query) {
        return all(query);
    }

    @Operation(summary = "读取角色详情")
    @GetMapping("/{id}")
    @QueryAction
    public Mono<Map<String, Object>> get(@PathVariable String id) {
        return service.dimension("role", id).map(service::role);
    }

    @Operation(summary = "创建角色")
    @PostMapping
    @SaveAction
    public Mono<Map<String, Object>> create(@RequestBody Map<String, Object> input) {
        return service.saveRole(input, true);
    }

    @Operation(summary = "批量保存角色")
    @PatchMapping
    @SaveAction
    public Mono<Void> save(@RequestBody Flux<Map<String, Object>> input) {
        return input.concatMap(row -> service.saveRole(row, false)).then();
    }

    @Operation(summary = "删除角色")
    @DeleteMapping("/{id}")
    @DeleteAction
    public Mono<Void> delete(@PathVariable String id) {
        return service.deleteRole(id);
    }

    @Operation(summary = "绑定角色用户")
    @PostMapping("/{id}/users/_bind")
    @SaveAction
    public Mono<Void> bind(@PathVariable String id, @RequestBody List<String> users) {
        return service.bind("role", id, users, false);
    }

    @Operation(summary = "解绑角色用户")
    @PostMapping("/{id}/users/_unbind")
    @SaveAction
    public Mono<Void> unbind(@PathVariable String id, @RequestBody List<String> users) {
        return service.bind("role", id, users, true);
    }

    @Operation(summary = "查询角色分组与角色树")
    @PostMapping("/group/detail/_query/tree")
    @QueryAction
    public Mono<List<Map<String, Object>>> groupTree(@RequestBody QueryParamEntity query) {
        return groups.query(query.noPaging())
                .collectList()
                .zipWith(service.allRoles(new QueryParamEntity()).collectList())
                .map(
                        tuple -> {
                            List<Map<String, Object>> rows = new ArrayList<>();
                            for (RoleGroupEntity group : tuple.getT1()) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("id", group.getId());
                                row.put("name", group.getName());
                                row.put("groupId", group.getId());
                                row.put("groupName", group.getName());
                                row.put("parentId", Objects.toString(group.getParentId(), ""));
                                List<Map<String, Object>> roles =
                                        tuple.getT2().stream()
                                                .filter(
                                                        role ->
                                                                group.getId()
                                                                        .equals(
                                                                                role.get(
                                                                                        "groupId")))
                                                .toList();
                                row.put("roles", roles);
                                row.put("children", roles);
                                rows.add(row);
                            }
                            return groupRoots(rows, null);
                        });
    }

    private List<Map<String, Object>> groupRoots(List<Map<String, Object>> all, String parent) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : all)
            if (Objects.equals(
                    Objects.toString(row.get("parentId"), ""), parent == null ? "" : parent)) {
                List<Object> children = new ArrayList<>(groupRoots(all, row.get("id").toString()));
                children.addAll((List<?>) row.get("children"));
                row.put("children", children);
                result.add(row);
            }
        return result;
    }
}
