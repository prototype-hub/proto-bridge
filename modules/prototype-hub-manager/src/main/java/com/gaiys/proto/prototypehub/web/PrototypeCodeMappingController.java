package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeCodeMappingService;
import com.gaiys.proto.prototypehub.web.request.CodeMappingRequests;
import com.gaiys.proto.prototypehub.web.response.CodeMappingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/** 成员当前动作与项目READ/EDIT取交集的固定页代码卡；导出只含显式选择的白名单内容，不授予访客访问。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "固定页面组件代码映射卡")
@RequestMapping(
        "/prototype-hub/versions/{versionId}/revisions/{revisionId}/pages/{pageId}/code-mappings")
public class PrototypeCodeMappingController {
    private final PrototypeCodeMappingService mappings;

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "按别名字面包含稳定分页读取当前页代码卡")
    public Mono<PagerResult<CodeMappingResponse.Card>> query(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody CodeMappingRequests.Query input) {
        return mappings.query(versionId, revisionId, pageId, input);
    }

    @GetMapping("/{id}")
    @QueryAction
    @Operation(summary = "读取精确范围代码卡和当前说明关联状态")
    public Mono<CodeMappingResponse.Card> detail(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @PathVariable String id) {
        return mappings.detail(versionId, revisionId, pageId, id);
    }

    @PostMapping
    @SaveAction
    @Operation(summary = "新建普通文本代码卡，不读取或执行代码")
    public Mono<CodeMappingResponse.Card> create(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody CodeMappingRequests.Create input) {
        return mappings.create(versionId, revisionId, pageId, input);
    }

    @PutMapping("/{id}")
    @SaveAction
    @Operation(summary = "CAS完整替换代码卡并显式维护说明关联")
    public Mono<CodeMappingResponse.Card> update(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @PathVariable String id,
            @Valid @RequestBody CodeMappingRequests.Update input) {
        return mappings.update(versionId, revisionId, pageId, id, input);
    }

    @PostMapping("/{id}/_delete")
    @SaveAction
    @Operation(summary = "CAS删除代码卡并保留原键回执")
    public Mono<Boolean> delete(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @PathVariable String id,
            @Valid @RequestBody CodeMappingRequests.Delete input) {
        return mappings.delete(versionId, revisionId, pageId, id, input);
    }

    @PostMapping("/_export")
    @QueryAction
    @Operation(summary = "下载当前页显式选定代码卡的可迁移JSON白名单内容")
    public Mono<ResponseEntity<byte[]>> export(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody CodeMappingRequests.Export input) {
        return mappings.export(versionId, revisionId, pageId, input)
                .map(
                        bytes ->
                                ResponseEntity.ok()
                                        .contentType(
                                                MediaType.parseMediaType(
                                                        "application/json;charset=UTF-8"))
                                        .contentLength(bytes.length)
                                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                                        .header("X-Content-Type-Options", "nosniff")
                                        .header(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=\"code-mapping-cards.json\"")
                                        .body(bytes));
    }

    @PostMapping("/_import")
    @SaveAction
    @Operation(summary = "在当前页单事务只新增有界卡包，原键只回本批当前或缺失卡")
    public Mono<CodeMappingResponse.ImportResult> importCards(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody Mono<CodeMappingRequests.Import> input) {
        return input.onErrorMap(
                        DataBufferLimitException.class,
                        ignored -> PrototypeCodeMappingService.tooLarge())
                .onErrorMap(
                        ServerWebInputException.class,
                        error ->
                                error.getCause() instanceof DataBufferLimitException
                                        ? PrototypeCodeMappingService.tooLarge()
                                        : error)
                .flatMap(pack -> mappings.importCards(versionId, revisionId, pageId, pack));
    }
}
