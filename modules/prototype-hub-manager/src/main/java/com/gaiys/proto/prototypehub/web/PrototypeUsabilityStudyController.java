package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeUsabilityStudyService;
import com.gaiys.proto.prototypehub.service.validation.UsabilityStudyValidator;
import com.gaiys.proto.prototypehub.web.request.UsabilityStudyRequests;
import com.gaiys.proto.prototypehub.web.response.UsabilityParticipationResponse;
import com.gaiys.proto.prototypehub.web.response.UsabilityStudyAggregate;
import com.gaiys.proto.prototypehub.web.response.UsabilityStudyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 成员菜单动作与当前项目READ/EDIT相交；结果及CSV均不接受分享访客凭据。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "固定修订基本可用性研究")
@RequestMapping("/prototype-hub/versions/{versionId}/revisions/{revisionId}/usability-studies")
public class PrototypeUsabilityStudyController {
    private final PrototypeUsabilityStudyService studies;

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "分页读取研究清单")
    public Mono<PagerResult<UsabilityStudyResponse.Summary>> query(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody UsabilityStudyRequests.Page input,
            ServerHttpResponse http) {
        http.getHeaders().setCacheControl("private, no-store");
        return studies.query(versionId, revisionId, input);
    }

    @GetMapping("/{id}")
    @QueryAction
    @Operation(summary = "读取研究定义及当前并发版本")
    public Mono<UsabilityStudyResponse> detail(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id,
            ServerHttpResponse http) {
        http.getHeaders().setCacheControl("private, no-store");
        return studies.detail(versionId, revisionId, id);
    }

    @PostMapping
    @SaveAction
    @Operation(summary = "新建仅内部可读的研究草稿")
    public Mono<UsabilityStudyResponse> create(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @Valid @RequestBody UsabilityStudyRequests.Fields input) {
        return studies.create(versionId, revisionId, input);
    }

    @PutMapping("/{id}")
    @SaveAction
    @Operation(summary = "CAS替换尚未开放的研究定义")
    public Mono<UsabilityStudyResponse> update(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id,
            @Valid @RequestBody UsabilityStudyRequests.Update input) {
        return studies.update(versionId, revisionId, id, input);
    }

    @PostMapping("/{id}/_open")
    @SaveAction
    @Operation(summary = "明确开放收集并永久冻结本研究题目")
    public Mono<UsabilityStudyResponse> open(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id,
            @Valid @RequestBody UsabilityStudyRequests.Mutation input) {
        return studies.open(versionId, revisionId, id, input);
    }

    @PostMapping("/{id}/_close")
    @SaveAction
    @Operation(summary = "关闭收集，既有本人撤回和成员结果读取仍按权可用")
    public Mono<UsabilityStudyResponse> close(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id,
            @Valid @RequestBody UsabilityStudyRequests.Mutation input) {
        return studies.close(versionId, revisionId, id, input);
    }

    @PostMapping("/{id}/_delete")
    @SaveAction
    @Operation(summary = "CAS原子移除研究及其全部答卷")
    public Mono<Boolean> delete(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id,
            @Valid @RequestBody UsabilityStudyRequests.Mutation input) {
        return studies.delete(versionId, revisionId, id, input);
    }

    @PostMapping("/{id}/responses/_query")
    @QueryAction
    @Operation(summary = "成员分页读取已提交答卷，不返回身份或逐条时间")
    public Mono<PagerResult<UsabilityParticipationResponse.Member>> responses(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id,
            @Valid @RequestBody UsabilityStudyRequests.Page input,
            ServerHttpResponse http) {
        http.getHeaders().setCacheControl("private, no-store");
        return studies.responsePage(versionId, revisionId, id, input);
    }

    @GetMapping("/{id}/responses/{responseId}")
    @QueryAction
    @Operation(summary = "读取同研究的已提交自由反馈")
    public Mono<UsabilityParticipationResponse.Member> response(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id,
            @PathVariable String responseId,
            ServerHttpResponse http) {
        http.getHeaders().setCacheControl("private, no-store");
        return studies.responseDetail(versionId, revisionId, id, responseId);
    }

    @GetMapping("/{id}/summary")
    @QueryAction
    @Operation(summary = "读取完整有界样本的结构化汇总，仅代表自报结果")
    public Mono<UsabilityStudyAggregate> summary(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id,
            ServerHttpResponse http) {
        http.getHeaders().setCacheControl("private, no-store");
        return studies.summary(versionId, revisionId, id);
    }

    @GetMapping("/{id}/summary.csv")
    @QueryAction
    @Operation(summary = "下载匿名结构化汇总，不含参与者文本/编号/身份/时间")
    public Mono<ResponseEntity<byte[]>> export(
            @PathVariable String versionId,
            @PathVariable String revisionId,
            @PathVariable String id) {
        return studies.summary(versionId, revisionId, id)
                .map(
                        value ->
                                ResponseEntity.ok()
                                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                                        .header("X-Content-Type-Options", "nosniff")
                                        .header(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=\"usability-summary.csv\"")
                                        .contentType(
                                                MediaType.parseMediaType("text/csv;charset=UTF-8"))
                                        .body(UsabilityStudyValidator.csv(value)));
    }
}
