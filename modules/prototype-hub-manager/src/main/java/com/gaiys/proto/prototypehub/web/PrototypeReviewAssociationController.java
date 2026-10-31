package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeReviewAssociationService;
import com.gaiys.proto.prototypehub.web.request.ReviewAssociationContextRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewAssociationCreateRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewAssociationQueryRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewAssociationRevokeRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewAssociationContextResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewAssociationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 人工核实后的可撤销线程授权；通用项目动作与服务层owner/ADMIN范围必须同时满足。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-project", name = "原型项目管理")
@RequestMapping("/prototype-hub/projects/{projectId}/review-associations")
@Tag(name = "历史评审授权")
public class PrototypeReviewAssociationController {
    private final PrototypeReviewAssociationService associations;

    @PostMapping("/_detail")
    @QueryAction
    @Operation(summary = "读取历史线程的授权来源与状态")
    public Mono<ReviewAssociationContextResponse> detail(
            @PathVariable String projectId,
            @Valid @RequestBody ReviewAssociationContextRequest input) {
        return associations.context(projectId, input);
    }

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "分页查询同来源固定范围的人工授权审计")
    public Mono<PagerResult<ReviewAssociationResponse>> query(
            @PathVariable String projectId,
            @Valid @RequestBody ReviewAssociationQueryRequest input) {
        return associations.query(projectId, input);
    }

    @PostMapping
    @SaveAction
    @Operation(summary = "记录线下核实依据并授权已验证邮箱访客访问历史对客线程")
    public Mono<ReviewAssociationResponse> create(
            @PathVariable String projectId,
            @Valid @RequestBody ReviewAssociationCreateRequest input) {
        return associations.create(projectId, input);
    }

    @PutMapping("/{associationId}/revoke")
    @SaveAction
    @Operation(summary = "CAS撤销历史评审授权，不删除历史回复")
    public Mono<ReviewAssociationResponse> revoke(
            @PathVariable String projectId,
            @PathVariable String associationId,
            @Valid @RequestBody ReviewAssociationRevokeRequest input) {
        return associations.revoke(projectId, associationId, input);
    }
}
