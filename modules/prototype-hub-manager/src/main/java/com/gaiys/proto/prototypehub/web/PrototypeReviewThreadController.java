package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeReviewAssignmentService;
import com.gaiys.proto.prototypehub.service.PrototypeReviewThreadService;
import com.gaiys.proto.prototypehub.web.request.ReviewAssignmentCandidateRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewAssignmentRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewMemberTextRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewMemberThreadOverviewRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewMessageQueryRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewPageRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewThreadQueryRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewThreadStateRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewAssigneeResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewAssignmentResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewMessageResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewThreadResponse;
import com.gaiys.proto.prototypehub.web.validation.ReviewImageMultipart;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
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
    private final ReviewImageMultipart multipart;
    private final PrototypeReviewAssignmentService assignments;

    @PostMapping("/_overview")
    @QueryAction
    @Operation(summary = "在项目编辑权限范围内按负责人分页查看固定修订全部页面反馈")
    public Mono<PagerResult<ReviewThreadResponse>> overview(
            @PathVariable String versionId,
            @Valid @RequestBody ReviewMemberThreadOverviewRequest input) {
        return threads.overviewMember(versionId, input);
    }

    @PostMapping("/{threadId}/assignment/_detail")
    @QueryAction
    @Operation(summary = "读取成员专用线程分派快照")
    public Mono<ReviewAssignmentResponse> assignment(
            @PathVariable String versionId,
            @PathVariable String threadId,
            @Valid @RequestBody ReviewPageRequest input) {
        return assignments.detail(versionId, threadId, input);
    }

    @PostMapping("/{threadId}/assignment/_candidates")
    @QueryAction
    @Operation(summary = "分页选择当前有效项目所有者或编辑者")
    public Mono<PagerResult<ReviewAssigneeResponse>> assignees(
            @PathVariable String versionId,
            @PathVariable String threadId,
            @Valid @RequestBody ReviewAssignmentCandidateRequest input) {
        return assignments.candidates(versionId, threadId, input);
    }

    @PutMapping("/{threadId}/assignment")
    @SaveAction
    @Operation(summary = "独立CAS分派或清除线程负责人，不修改客户可见元数据")
    public Mono<ReviewAssignmentResponse> assign(
            @PathVariable String versionId,
            @PathVariable String threadId,
            @Valid @RequestBody ReviewAssignmentRequest input) {
        return assignments.assign(versionId, threadId, input);
    }

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

    @PostMapping(value = "/{threadId}/messages-with-image", consumes = "multipart/form-data")
    @SaveAction
    @Operation(summary = "向已有线程追加一张私有PNG/JPEG回复或内部备注")
    public Mono<ReviewMessageResponse> image(
            @PathVariable String versionId,
            @PathVariable String threadId,
            ServerWebExchange exchange) {
        return multipart.read(
                exchange,
                ReviewMemberTextRequest.class,
                new Class<?>[] {Default.class},
                (input, image) -> threads.imageMember(versionId, threadId, input, image));
    }

    @PostMapping("/{threadId}/messages/{messageId}/image")
    @QueryAction
    @Operation(summary = "按项目EDIT权限读取当前线程的回复图片")
    public Mono<ResponseEntity<org.springframework.core.io.Resource>> image(
            @PathVariable String versionId,
            @PathVariable String threadId,
            @PathVariable String messageId,
            @Valid @RequestBody ReviewPageRequest input) {
        return threads.imageMember(versionId, threadId, messageId, input);
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
