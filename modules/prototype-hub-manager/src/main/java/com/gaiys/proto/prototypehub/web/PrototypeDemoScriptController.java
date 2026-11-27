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
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 菜单query/save与当前项目READ/EDIT取交集；保存入口不额外要求query。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "固定修订任务演示脚本")
@RequestMapping("/prototype-hub/versions/{versionId}/revisions/{revisionId}/demo-scripts")
public class PrototypeDemoScriptController {
    private final PrototypeDemoScriptService scripts;

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "分页读取固定修订脚本摘要，不读取正文或步骤")
    public Mono<PagerResult<DemoScriptResponse.Summary>> query(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody DemoScriptRequests.Page input) {
        return scripts.query(versionId, revisionId, input);
    }

    @GetMapping("/{id}")
    @QueryAction
    @Operation(summary = "读取精确脚本全文或显式刷新并发版本")
    public Mono<DemoScriptResponse> detail(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id) {
        return scripts.detail(versionId, revisionId, id);
    }

    @PostMapping
    @SaveAction
    @Operation(summary = "新建人工演示脚本，默认仅内部读取")
    public Mono<DemoScriptResponse> create(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody DemoScriptRequests.Fields input) {
        return scripts.create(versionId, revisionId, input);
    }

    @PutMapping("/{id}")
    @SaveAction
    @Operation(summary = "CAS完整替换脚本，可显式开放完整正文给现有分享读者")
    public Mono<DemoScriptResponse> update(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id,
            @Valid @RequestBody DemoScriptRequests.Update input) {
        return scripts.update(versionId, revisionId, id, input);
    }

    @PostMapping("/{id}/_delete")
    @SaveAction
    @Operation(summary = "CAS删除脚本，未知结果须以原键及原payload重试")
    public Mono<Boolean> delete(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id,
            @Valid @RequestBody DemoScriptRequests.Delete input) {
        return scripts.delete(versionId, revisionId, id, input);
    }
}
