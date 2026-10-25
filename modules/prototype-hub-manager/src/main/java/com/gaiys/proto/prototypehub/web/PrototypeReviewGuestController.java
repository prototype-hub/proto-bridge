package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.enums.ReviewVerificationLevel;
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
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/** 按身份保障等级选择项目访客管理入口；分享动作权限还须与服务层项目 SHARE 范围取交集。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-share", name = "原型分享管理")
@Tag(name = "原型访客管理")
@RequestMapping("/prototype-hub/projects/{projectId}/review-guests")
public class PrototypeReviewGuestController {
    private final PrototypeReviewGuestService guests;

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "按保障等级分页查询项目访客", description = "默认邮箱访客且只返回脱敏邮箱；未验证访客没有邮箱")
    public Mono<PagerResult<ReviewGuestResponse>> query(
            @PathVariable String projectId, @Valid @RequestBody ReviewGuestQueryRequest input) {
        return guests.query(projectId, input);
    }

    @PutMapping("/{guestId}/enabled")
    @SaveAction
    @Operation(summary = "启用或停用项目访客", description = "状态切换撤销旧会话；邮箱身份需重新验证，未验证身份不能找回或续期，过期行不可变更")
    public Mono<ReviewGuestResponse> enabled(
            @PathVariable String projectId,
            @PathVariable String guestId,
            @RequestParam MultiValueMap<String, String> query,
            @Valid @RequestBody ReviewGuestEnabledRequest input) {
        var selectors = query.get("verificationLevel");
        ReviewVerificationLevel level = ReviewVerificationLevel.email;
        if (selectors != null) {
            if (selectors.size() != 1)
                throw new ServerWebInputException("Invalid verification level");
            level =
                    switch (selectors.getFirst()) {
                        case "email" -> ReviewVerificationLevel.email;
                        case "unverified" -> ReviewVerificationLevel.unverified;
                        default -> throw new ServerWebInputException("Invalid verification level");
                    };
        }
        return guests.setEnabled(projectId, guestId, input, level);
    }
}
