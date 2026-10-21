package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.enums.ProjectRole;
import com.gaiys.proto.prototypehub.service.PrototypeDeletionService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectService;
import com.gaiys.proto.prototypehub.web.request.ProjectRequest;
import com.gaiys.proto.prototypehub.web.response.ProjectResponse;
import com.gaiys.proto.prototypehub.web.response.ProjectUsageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.DeleteAction;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 提供原型项目的管理接口。
 *
 * <p>请求校验和操作权限由 Web 层及 hsweb 注解承接，项目删除约束与文件清理由对应服务完成。
 */
@RestController
@Tag(name = "原型项目管理")
@AllArgsConstructor
@Authorize
@Resource(id = "prototype-hub-project", name = "原型项目管理")
@RequestMapping("/prototype-hub/projects")
public class PrototypeProjectController {
    private final PrototypeProjectService projects;
    private final PrototypeDeletionService deletion;
    private final PrototypeProjectAccessService access;

    @GetMapping
    @QueryAction
    @Operation(summary = "查询原型项目列表")
    public Flux<ProjectResponse> list() {
        return access.readableProjects();
    }

    /** 沿用项目查询权限，统计整个安装实例，不新增菜单权限或按创建者缩小统计。 */
    @GetMapping("/_usage")
    @QueryAction
    @Operation(summary = "查询部署身份及项目容量")
    public Mono<ProjectUsageResponse> usage() {
        return access.current().then(projects.usage());
    }

    @GetMapping("/{id}")
    @QueryAction
    @Operation(summary = "查询原型项目详情")
    public Mono<ProjectResponse> detail(@PathVariable String id) {
        return access.requireProject(id, Action.READ).flatMap(access::response);
    }

    @PostMapping
    @SaveAction
    @Operation(summary = "创建原型项目")
    public Mono<ProjectResponse> create(
            @Valid @RequestBody ProjectRequest input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return access.current()
                .flatMap(actor -> projects.create(input, actor.userId(), key))
                .flatMap(project -> access.requireProject(project.getId(), Action.READ))
                .flatMap(access::response);
    }

    @PutMapping("/{id}")
    @SaveAction
    @Operation(summary = "修改原型项目")
    public Mono<ProjectResponse> update(
            @PathVariable String id, @Valid @RequestBody ProjectRequest input) {
        return Authentication.currentReactive()
                .flatMap(auth -> projects.update(id, input, auth.getUser().getId()))
                .flatMap(project -> access.requireProject(project.getId(), Action.READ))
                .flatMap(access::response);
    }

    @DeleteMapping("/{id}")
    @DeleteAction
    @Operation(
            summary = "删除空原型项目",
            description = "仅当项目下不存在版本时删除；存在版本时须先删除全部版本",
            responses = @ApiResponse(responseCode = "409", description = "项目下仍存在版本"))
    public Mono<ProjectResponse> delete(@PathVariable String id) {
        return access.current()
                .flatMap(
                        actor ->
                                deletion.deleteProject(id)
                                        .flatMap(
                                                project ->
                                                        access.claimRequired(project)
                                                                .map(
                                                                        pending ->
                                                                                ProjectResponse
                                                                                        .from(
                                                                                                project,
                                                                                                actor
                                                                                                                .administrator()
                                                                                                        ? ProjectRole
                                                                                                                .admin
                                                                                                                .name()
                                                                                                        : ProjectRole
                                                                                                                .owner
                                                                                                                .name(),
                                                                                                pending))));
    }
}
