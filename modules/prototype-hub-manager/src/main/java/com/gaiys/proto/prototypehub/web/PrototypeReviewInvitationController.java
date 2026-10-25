package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeReviewInvitationService;
import com.gaiys.proto.prototypehub.web.request.ReviewInvitationCreateRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewInvitationQueryRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewInvitationRevokeRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewInvitationCreatedResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewInvitationResponse;
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
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 分享动作权限与服务层当前项目SHARE范围取交集，不新增后台身份或邮件投递入口。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-share", name = "原型分享管理")
@Tag(name = "原型邮箱邀请")
@RequestMapping("/prototype-hub/shares/{shareId}/review-invitations")
public class PrototypeReviewInvitationController {
    private final PrototypeReviewInvitationService invitations;

    @PostMapping
    @SaveAction
    @Operation(summary = "创建固定分享邮箱邀请", description = "凭据仅返回一次，由用户手动复制；不发送邀请邮件")
    public Mono<ReviewInvitationCreatedResponse> create(
            @PathVariable String shareId,
            @Valid @RequestBody ReviewInvitationCreateRequest input,
            ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        return invitations.create(shareId, input);
    }

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "分页查询脱敏邀请状态")
    public Mono<PagerResult<ReviewInvitationResponse>> query(
            @PathVariable String shareId, @Valid @RequestBody ReviewInvitationQueryRequest input) {
        return invitations.query(shareId, input);
    }

    @PutMapping("/{invitationId}/revoke")
    @SaveAction
    @Operation(summary = "按版本撤销未使用邀请", description = "不撤销已有访客或会话；终态不可变更")
    public Mono<ReviewInvitationResponse> revoke(
            @PathVariable String shareId,
            @PathVariable String invitationId,
            @Valid @RequestBody ReviewInvitationRevokeRequest input) {
        return invitations.revoke(shareId, invitationId, input);
    }
}
