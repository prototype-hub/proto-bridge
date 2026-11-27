package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeApprovalService;
import com.gaiys.proto.prototypehub.web.request.ApprovalRequests;
import com.gaiys.proto.prototypehub.web.response.ApprovalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 内部固定修订的正式决策入口；动作权限始终与当前项目READ/EDIT相交。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "固定修订批准与发布")
@RequestMapping("/prototype-hub/versions/{versionId}/revisions/{revisionId}/approval")
public class PrototypeApprovalController {
    private final PrototypeApprovalService approvals;

    @GetMapping
    @QueryAction
    @Operation(summary = "读取固定修订正式状态与内容摘要，不把浏览或线程解决当作批准")
    public Mono<ApprovalResponse> detail(
            @PathVariable String versionId, @PathVariable String revisionId) {
        return approvals.detail(versionId, revisionId);
    }

    @PostMapping("/_act")
    @SaveAction
    @Operation(summary = "显式批准、撤销批准、发布或撤下固定修订；不部署或改变访问权")
    public Mono<ApprovalResponse> act(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody ApprovalRequests.Act input) {
        return approvals.act(versionId, revisionId, input);
    }

    @PostMapping("/_history")
    @QueryAction
    @Operation(summary = "按固定范围分页读取正式决策历史")
    public Mono<PagerResult<ApprovalResponse.History>> history(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody ApprovalRequests.History input) {
        return approvals.history(versionId, revisionId, input);
    }
}
