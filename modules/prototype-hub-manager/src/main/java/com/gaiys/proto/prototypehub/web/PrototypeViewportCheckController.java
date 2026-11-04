package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeViewportCheckService;
import com.gaiys.proto.prototypehub.web.request.ViewportCheckRequests;
import com.gaiys.proto.prototypehub.web.response.ViewportCheckResponse;
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

/** 内部成员专用，菜单动作与项目READ/EDIT取交集；不接受分享或访客授权。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "固定页面人工视口自测")
@RequestMapping(
        "/prototype-hub/versions/{versionId}/revisions/{revisionId}/pages/{pageId}/viewport-checks")
public class PrototypeViewportCheckController {
    private final PrototypeViewportCheckService checks;

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "按项目READ分页读取固定页面人工记录")
    public Mono<PagerResult<ViewportCheckResponse>> query(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody ViewportCheckRequests.Query input) {
        return checks.query(versionId, revisionId, pageId, input);
    }

    @PostMapping
    @SaveAction
    @Operation(summary = "按项目EDIT保存人工场景、视口和观察，不执行自动测试")
    public Mono<ViewportCheckResponse> create(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody ViewportCheckRequests.Fields input) {
        return checks.create(versionId, revisionId, pageId, input);
    }

    @PutMapping("/{checkId}")
    @SaveAction
    @Operation(summary = "CAS更新当前固定页面的人工记录")
    public Mono<ViewportCheckResponse> update(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @PathVariable String checkId,
            @Valid @RequestBody ViewportCheckRequests.Update input) {
        return checks.update(versionId, revisionId, pageId, checkId, input);
    }

    @PostMapping("/{checkId}/_delete")
    @SaveAction
    @Operation(summary = "CAS删除人工记录；未知结果可用原请求键重试")
    public Mono<Boolean> delete(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @PathVariable String checkId,
            @Valid @RequestBody ViewportCheckRequests.Delete input) {
        return checks.delete(versionId, revisionId, pageId, checkId, input);
    }
}
