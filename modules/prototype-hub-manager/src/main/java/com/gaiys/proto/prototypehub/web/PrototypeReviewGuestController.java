package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeReviewGuestService;
import com.gaiys.proto.prototypehub.web.request.ReviewGuestEnabledRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewGuestQueryRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewGuestResponse;
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

/** 已验证邮箱访客的项目管理入口；分享动作权限还须与服务层项目 SHARE 范围取交集。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-share", name = "原型分享管理")
@Tag(name = "原型邮箱访客管理")
@RequestMapping("/prototype-hub/projects/{projectId}/review-guests")
public class PrototypeReviewGuestController {
    private final PrototypeReviewGuestService guests;

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "分页查询项目邮箱访客", description = "仅返回脱敏邮箱，不包含待验证收件人")
    public Mono<PagerResult<ReviewGuestResponse>> query(
            @PathVariable String projectId, @Valid @RequestBody ReviewGuestQueryRequest input) {
        return guests.query(projectId, input);
    }

    @PutMapping("/{guestId}/enabled")
    @SaveAction
    @Operation(summary = "启用或停用项目邮箱访客", description = "真实状态切换撤销旧会话和验证码，恢复后须重新获取验证码")
    public Mono<ReviewGuestResponse> enabled(
            @PathVariable String projectId,
            @PathVariable String guestId,
            @Valid @RequestBody ReviewGuestEnabledRequest input) {
        return guests.setEnabled(projectId, guestId, input);
    }
}
