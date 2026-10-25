package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeReviewThreadService;
import com.gaiys.proto.prototypehub.web.request.ReviewMemberTextRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewMessageQueryRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewPageRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewThreadQueryRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewThreadStateRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewMessageResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewThreadResponse;
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

/** 管理查询也要求项目EDIT；菜单动作不能替代项目范围，内部备注不参与公开元数据。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "页面评审管理")
@RequestMapping("/prototype-hub/versions/{versionId}/review-threads")
public class PrototypeReviewThreadController {
    private final PrototypeReviewThreadService threads;

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "按固定页面分页管理评审线程")
    public Mono<PagerResult<ReviewThreadResponse>> query(
            @PathVariable String versionId, @Valid @RequestBody ReviewThreadQueryRequest input) {
        return threads.queryMember(versionId, input);
    }

    @PostMapping("/{threadId}/_detail")
    @QueryAction
    @Operation(summary = "按固定页面定位评审线程")
    public Mono<ReviewThreadResponse> detail(
            @PathVariable String versionId,
            @PathVariable String threadId,
            @Valid @RequestBody ReviewPageRequest input) {
        return threads.detailMember(versionId, threadId, input);
    }

    @PostMapping("/{threadId}/messages/_query")
    @QueryAction
    @Operation(summary = "分页读取公开回复和内部备注")
    public Mono<PagerResult<ReviewMessageResponse>> messages(
            @PathVariable String versionId,
            @PathVariable String threadId,
            @Valid @RequestBody ReviewMessageQueryRequest input) {
        return threads.messagesMember(versionId, threadId, input);
    }

    @PostMapping("/{threadId}/messages")
    @SaveAction
    @Operation(summary = "明确追加对客回复或内部备注")
    public Mono<ReviewMessageResponse> reply(
            @PathVariable String versionId,
            @PathVariable String threadId,
            @Valid @RequestBody ReviewMemberTextRequest input) {
        return threads.replyMember(versionId, threadId, input);
    }

    @PutMapping("/{threadId}/status")
    @SaveAction
    @Operation(summary = "通过状态CAS解决或重开线程")
    public Mono<ReviewThreadResponse> state(
            @PathVariable String versionId,
            @PathVariable String threadId,
            @Valid @RequestBody ReviewThreadStateRequest input) {
        return threads.stateMember(versionId, threadId, input);
    }
}
