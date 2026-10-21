package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeOwnershipService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectMemberService;
import com.gaiys.proto.prototypehub.web.request.OwnershipMigrationRequest;
import com.gaiys.proto.prototypehub.web.request.ProjectClaimRequest;
import com.gaiys.proto.prototypehub.web.request.ProjectMemberRequest;
import com.gaiys.proto.prototypehub.web.response.MemberCandidateResponse;
import com.gaiys.proto.prototypehub.web.response.OwnershipMigrationResponse;
import com.gaiys.proto.prototypehub.web.response.ProjectMemberResponse;
import com.gaiys.proto.prototypehub.web.response.ProjectResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 项目成员与历史认领接口；沿用项目动作权限，并由集中资产范围边界校验所有权。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-project", name = "原型项目管理")
@RequestMapping("/prototype-hub/projects")
@Tag(name = "原型项目成员")
public class PrototypeProjectMemberController {
    private final PrototypeProjectMemberService members;
    private final PrototypeOwnershipService ownership;

    @GetMapping("/{id}/members")
    @QueryAction
    @Operation(summary = "查询项目成员，仅项目所有者或管理员")
    public Flux<ProjectMemberResponse> members(@PathVariable String id) {
        return members.members(id);
    }

    @GetMapping("/{id}/member-candidates")
    @QueryAction
    @Operation(summary = "按字面姓名或登录名查找最多二十个有效候选成员")
    public Flux<MemberCandidateResponse> candidates(
            @PathVariable String id, @RequestParam(required = false) String keyword) {
        return members.candidates(id, keyword);
    }

    @PutMapping("/{id}/members/{userId}")
    @SaveAction
    @Operation(summary = "添加或修改项目成员角色")
    public Mono<ProjectMemberResponse> put(
            @PathVariable String id,
            @PathVariable String userId,
            @Valid @RequestBody ProjectMemberRequest input) {
        return members.put(id, userId, input.role());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @SaveAction
    @Operation(summary = "移除项目成员并撤销内部预览授权", description = "成员管理沿用项目保存动作；不等同于删除项目")
    public Mono<Boolean> remove(@PathVariable String id, @PathVariable String userId) {
        return members.remove(id, userId);
    }

    @PostMapping("/{id}/claim")
    @SaveAction
    @Operation(summary = "管理员为缺少有效所有者的项目指定所有者")
    public Mono<ProjectResponse> claim(
            @PathVariable String id, @Valid @RequestBody ProjectClaimRequest input) {
        return ownership.claim(id, input.userId());
    }

    @PostMapping("/_ownership-migration")
    @SaveAction
    @Operation(
            summary = "管理员显式回填历史项目所有权",
            description = "apply=false 仅读取计数，apply=true 幂等赋予有效创建者；无效创建者保留待认领")
    public Mono<OwnershipMigrationResponse> migrate(
            @Valid @RequestBody OwnershipMigrationRequest input) {
        return ownership.migrate(input.apply());
    }
}
