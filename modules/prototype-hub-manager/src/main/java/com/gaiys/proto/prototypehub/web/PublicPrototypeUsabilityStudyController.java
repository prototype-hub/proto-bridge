package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeUsabilityStudyService;
import com.gaiys.proto.prototypehub.web.request.UsabilityStudyRequests;
import com.gaiys.proto.prototypehub.web.response.UsabilityParticipationResponse.MutationResult;
import com.gaiys.proto.prototypehub.web.response.UsabilityStudyResponse;
import com.gaiys.proto.prototypehub.web.security.ReviewRequestSecurity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 位于现有review Cookie精确Path内；只读本研究定义/本人答卷，写入逐次Origin及CSRF。 */
@RestController
@RequiredArgsConstructor
@Authorize(ignore = true)
@Tag(name = "分享可用性研究参加")
@RequestMapping(
        value = "/prototype-hub/public/shares/{token}/review/usability-studies",
        consumes = MediaType.APPLICATION_JSON_VALUE)
public class PublicPrototypeUsabilityStudyController {
    private final PrototypeUsabilityStudyService studies;
    private final ReviewRequestSecurity security;

    @PostMapping("/_query")
    @Operation(summary = "读取当前分享精确修订中已开放过的研究，不建立参加记录")
    public Mono<PagerResult<UsabilityStudyResponse.PublicSummary>> query(
            @PathVariable String token,
            @Valid @RequestBody UsabilityStudyRequests.PublicQuery input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        String cookie = security.contextCookie(exchange);
        return studies.publicQuery(token, input, cookie)
                .doOnNext(ignored -> security.cookie(exchange, token, cookie));
    }

    @PostMapping("/{id}/context")
    @Operation(summary = "读取冻结题目和本人已有填写；匿名读取不等于同意")
    public Mono<UsabilityStudyResponse.PublicContext> context(
            @PathVariable String token,
            @PathVariable String id,
            @Valid @RequestBody UsabilityStudyRequests.PublicGrant input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        String cookie = security.contextCookie(exchange);
        return studies.publicContext(token, id, input, cookie)
                .doOnNext(ignored -> security.cookie(exchange, token, cookie));
    }

    @PostMapping("/{id}/consent")
    @Operation(summary = "明确同意冻结研究内容后建立本人参加记录")
    public Mono<MutationResult> consent(
            @PathVariable String token,
            @PathVariable String id,
            @Valid @RequestBody UsabilityStudyRequests.Consent input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        return studies.consent(token, id, input, security.unsafeCookie(exchange));
    }

    @PostMapping("/{id}/progress")
    @Operation(summary = "CAS主动保存本人自报与问卷草稿，不采集行为或计时")
    public Mono<MutationResult> progress(
            @PathVariable String token,
            @PathVariable String id,
            @Valid @RequestBody UsabilityStudyRequests.Progress input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        return studies.progress(token, id, input, security.unsafeCookie(exchange), false);
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "校验完整填写后明确提交不可编辑结果")
    public Mono<MutationResult> submit(
            @PathVariable String token,
            @PathVariable String id,
            @Valid @RequestBody UsabilityStudyRequests.Progress input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        return studies.progress(token, id, input, security.unsafeCookie(exchange), true);
    }

    @PostMapping("/{id}/withdraw")
    @Operation(summary = "撤回本人参加并清除全部答案/反馈；关闭收集后仍可撤回")
    public Mono<MutationResult> withdraw(
            @PathVariable String token,
            @PathVariable String id,
            @Valid @RequestBody UsabilityStudyRequests.Withdraw input,
            ServerWebExchange exchange) {
        security.origin(exchange);
        return studies.withdraw(token, id, input, security.unsafeCookie(exchange));
    }
}
