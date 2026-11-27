package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypePageTraceService;
import com.gaiys.proto.prototypehub.web.request.PageTraceRequests;
import com.gaiys.proto.prototypehub.web.response.PageTraceResponse;
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

/** 仅成员的query/save动作与当前项目READ/EDIT取交集，没有访客或分享授权。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "固定修订页面关系与需求追踪")
@RequestMapping("/prototype-hub/versions/{versionId}/revisions/{revisionId}/page-traceability")
public class PrototypePageTraceController {
    private final PrototypePageTraceService trace;

    @PostMapping("/relations/_query")
    @QueryAction
    @Operation(summary = "分页读取人工有向关系；当前分页即图邻域")
    public Mono<PagerResult<PageTraceResponse.Relation>> queryRelations(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody PageTraceRequests.RelationQuery input) {
        return trace.queryRelations(versionId, revisionId, input);
    }

    @GetMapping("/relations/{id}")
    @QueryAction
    @Operation(summary = "读取精确人工关系或刷新并发版本")
    public Mono<PageTraceResponse.Relation> relation(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id) {
        return trace.relation(versionId, revisionId, id);
    }

    @PostMapping("/relations")
    @SaveAction
    @Operation(summary = "新建人工页面关系")
    public Mono<PageTraceResponse.Relation> createRelation(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody PageTraceRequests.RelationFields input) {
        return trace.createRelation(versionId, revisionId, input);
    }

    @PutMapping("/relations/{id}")
    @SaveAction
    @Operation(summary = "CAS完整替换人工页面关系")
    public Mono<PageTraceResponse.Relation> updateRelation(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id,
            @Valid @RequestBody PageTraceRequests.RelationUpdate input) {
        return trace.updateRelation(versionId, revisionId, id, input);
    }

    @PostMapping("/relations/{id}/_delete")
    @SaveAction
    @Operation(summary = "CAS删除人工关系并保留原键回执")
    public Mono<Boolean> deleteRelation(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id,
            @Valid @RequestBody PageTraceRequests.Delete input) {
        return trace.deleteRelation(versionId, revisionId, id, input);
    }

    @GetMapping("/pages/{pageId}/context")
    @QueryAction
    @Operation(summary = "读取入口声明、来源版本及changed/missing状态")
    public Mono<PageTraceResponse.Context> context(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId) {
        return trace.context(versionId, revisionId, pageId);
    }

    @PutMapping("/pages/{pageId}/context")
    @SaveAction
    @Operation(summary = "CAS完整替换入口声明和有界来源指针")
    public Mono<PageTraceResponse.Context> updateContext(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody PageTraceRequests.ContextUpdate input) {
        return trace.updateContext(versionId, revisionId, pageId, input);
    }

    @PostMapping("/entries/_query")
    @QueryAction
    @Operation(summary = "分页读取人工入口页声明")
    public Mono<PagerResult<PageTraceResponse.Entry>> entries(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody PageTraceRequests.Page input) {
        return trace.entries(versionId, revisionId, input);
    }

    @PostMapping("/pages/{pageId}/sources/_query")
    @QueryAction
    @Operation(summary = "分页选择同页来源或同修订资料")
    public Mono<PagerResult<PageTraceResponse.SourceCandidate>> sourceCandidates(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody PageTraceRequests.SourceQuery input) {
        return trace.sourceCandidates(versionId, revisionId, pageId, input);
    }

    @GetMapping("/pages/{pageId}/sources/{kind}/{id}")
    @QueryAction
    @Operation(summary = "读取精确范围来源纯文本；不返回存储路径")
    public Mono<PageTraceResponse.SourceDetail> sourceDetail(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @PathVariable String kind,
            @PathVariable String id) {
        return trace.sourceDetail(versionId, revisionId, pageId, kind, id);
    }

    @PostMapping("/pages/{pageId}/decisions/_query")
    @QueryAction
    @Operation(summary = "分页读取人工决策记录，不代表正式批准")
    public Mono<PagerResult<PageTraceResponse.Decision>> queryDecisions(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody PageTraceRequests.Page input) {
        return trace.queryDecisions(versionId, revisionId, pageId, input);
    }

    @GetMapping("/pages/{pageId}/decisions/{id}")
    @QueryAction
    @Operation(summary = "读取精确页面决策或刷新并发版本")
    public Mono<PageTraceResponse.Decision> decision(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @PathVariable String id) {
        return trace.decision(versionId, revisionId, pageId, id);
    }

    @PostMapping("/pages/{pageId}/decisions")
    @SaveAction
    @Operation(summary = "新建人工决策与理由，作者时间由服务端记录")
    public Mono<PageTraceResponse.Decision> createDecision(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @Valid @RequestBody PageTraceRequests.DecisionFields input) {
        return trace.createDecision(versionId, revisionId, pageId, input);
    }

    @PutMapping("/pages/{pageId}/decisions/{id}")
    @SaveAction
    @Operation(summary = "CAS完整替换人工决策")
    public Mono<PageTraceResponse.Decision> updateDecision(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @PathVariable String id,
            @Valid @RequestBody PageTraceRequests.DecisionUpdate input) {
        return trace.updateDecision(versionId, revisionId, pageId, id, input);
    }

    @PostMapping("/pages/{pageId}/decisions/{id}/_delete")
    @SaveAction
    @Operation(summary = "CAS删除人工决策并保留原键回执")
    public Mono<Boolean> deleteDecision(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String pageId,
            @PathVariable String id,
            @Valid @RequestBody PageTraceRequests.Delete input) {
        return trace.deleteDecision(versionId, revisionId, pageId, id, input);
    }
}
