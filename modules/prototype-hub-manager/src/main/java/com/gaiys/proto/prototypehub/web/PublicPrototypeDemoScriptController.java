package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeDemoScriptService;
import com.gaiys.proto.prototypehub.web.request.DemoScriptRequests;
import com.gaiys.proto.prototypehub.web.response.DemoScriptResponse;
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
import reactor.core.publisher.Mono;

/** 现有分享精确修订授权下的只读脚本入口；凭据仅在请求体，无访客或会话写入。 */
@RestController
@RequiredArgsConstructor
@Authorize(ignore = true)
@Tag(name = "公开分享任务演示脚本")
@RequestMapping(
        value = "/prototype-hub/public/shares/{token}/demo-scripts",
        consumes = MediaType.APPLICATION_JSON_VALUE)
public class PublicPrototypeDemoScriptController {
    private final PrototypeDemoScriptService scripts;

    @PostMapping("/_query")
    @Operation(summary = "分页读取已显式开放脚本的三个摘要字段")
    public Mono<PagerResult<DemoScriptResponse.PublicSummary>> query(
            @PathVariable String token, @Valid @RequestBody DemoScriptRequests.PublicQuery input) {
        return scripts.publicQuery(token, input);
    }

    @PostMapping("/{id}/context")
    @Operation(summary = "读取已开放脚本七个内容字段，不返回内部范围或并发元数据")
    public Mono<DemoScriptResponse.PublicContext> context(
            @PathVariable String token,
            @PathVariable String id,
            @Valid @RequestBody DemoScriptRequests.PublicContext input) {
        return scripts.publicContext(token, id, input);
    }
}
