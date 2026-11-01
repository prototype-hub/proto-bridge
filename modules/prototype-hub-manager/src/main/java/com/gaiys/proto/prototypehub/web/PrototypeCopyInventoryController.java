package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeCopyInventoryService;
import com.gaiys.proto.prototypehub.web.request.CopyInventoryRequests;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 当前内部成员专用；菜单动作与项目READ/EDIT取交集，无分享、访客或当前修订回退入口。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "固定页面文案清单")
@RequestMapping(
        "/prototype-hub/versions/{versionId}/revisions/{revisionId}/pages/{pageId}/copy-inventory")
public class PrototypeCopyInventoryController {
    private final PrototypeCopyInventoryService inventory;

    @GetMapping
    @QueryAction
    @Operation(summary = "读取固定页提取状态与完整身份")
    public Mono<CopyInventoryResponse> context(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId) {
        return inventory.context(versionId, revisionId, pageId);
    }

    @PostMapping("/_extract")
    @SaveAction
    @Operation(summary = "首次提取静态原文；重提返回原快照，不覆盖人工内容")
    public Mono<CopyInventoryResponse> extract(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody CopyInventoryRequests.Mutation input) {
        return inventory.extract(versionId, revisionId, pageId, input);
    }

    @PostMapping("/entries/_query")
    @QueryAction
    @Operation(summary = "统一分页查询静态与人工文案，数据库筛选和真实计数")
    public Mono<CopyInventoryResponse.Page> query(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody CopyInventoryRequests.Query input) {
        return inventory.query(versionId, revisionId, pageId, input);
    }

    @PostMapping("/entries")
    @SaveAction
    @Operation(summary = "新增人工文案补充；空提取或预算失败仍可维护")
    public Mono<CopyInventoryResponse.Saved> create(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody CopyInventoryRequests.Fields input) {
        return inventory.create(versionId, revisionId, pageId, input);
    }

    @PutMapping("/entries/{entryId}")
    @SaveAction
    @Operation(summary = "CAS维护业务词、备注和需求链接；仅人工条目可改原文")
    public Mono<CopyInventoryResponse.Saved> update(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @PathVariable String entryId,
            @Valid @RequestBody CopyInventoryRequests.Update input) {
        return inventory.update(versionId, revisionId, pageId, entryId, input);
    }

    @PostMapping("/entries/{entryId}/_delete")
    @SaveAction
    @Operation(summary = "CAS删除人工补充，不删除静态原文或外部需求")
    public Mono<CopyInventoryResponse.Deleted> delete(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @PathVariable String entryId,
            @Valid @RequestBody CopyInventoryRequests.Delete input) {
        return inventory.delete(versionId, revisionId, pageId, entryId, input);
    }

    @PostMapping("/_export")
    @QueryAction
    @Operation(summary = "导出固定页完整CSV或JSON清单，不受列表筛选或分页影响")
    public Mono<Void> export(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody CopyInventoryRequests.Export input,
            ServerHttpResponse response) {
        return inventory.export(versionId, revisionId, pageId, input, response);
    }
}
