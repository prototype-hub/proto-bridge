package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeApprovalService;
import com.gaiys.proto.prototypehub.web.request.ApprovalRequests;
import com.gaiys.proto.prototypehub.web.response.ApprovalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 公开分享按现有读取凭据读取脱敏正式状态；不建立会话，不写入，不依赖评审开关。 */
@RestController
@RequiredArgsConstructor
@Authorize(ignore = true)
@Tag(name = "公开分享正式状态")
@RequestMapping(
        value = "/prototype-hub/public/shares/{token}/approval",
        consumes = MediaType.APPLICATION_JSON_VALUE)
public class PublicPrototypeApprovalController {
    private final PrototypeApprovalService approvals;

    @PostMapping("/context")
    @Operation(summary = "读取已授权固定修订的批准/发布标记与时间，不返回作者、备注或历史")
    public Mono<ApprovalResponse.PublicContext> context(
            @PathVariable String token, @Valid @RequestBody ApprovalRequests.PublicContext input) {
        return approvals.publicContext(token, input);
    }
}
