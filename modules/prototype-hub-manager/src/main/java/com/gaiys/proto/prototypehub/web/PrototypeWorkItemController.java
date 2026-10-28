package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeWorkItemService;
import com.gaiys.proto.prototypehub.web.request.WorkItemCreateRequest;
import com.gaiys.proto.prototypehub.web.request.WorkItemDeleteRequest;
import com.gaiys.proto.prototypehub.web.request.WorkItemQueryRequest;
import com.gaiys.proto.prototypehub.web.request.WorkItemUpdateRequest;
import com.gaiys.proto.prototypehub.web.response.WorkItemResponse;
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

/** 工作项仅对当前项目成员开放；菜单动作与项目READ/EDIT必须同时满足。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "页面工作项关联")
@RequestMapping("/prototype-hub/versions/{versionId}/work-items")
public class PrototypeWorkItemController {
    private final PrototypeWorkItemService workItems;

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "按项目READ权限分页读取固定修订页面的工作项关联")
    public Mono<PagerResult<WorkItemResponse>> query(
            @PathVariable String versionId, @Valid @RequestBody WorkItemQueryRequest input) {
        return workItems.query(versionId, input);
    }

    @PostMapping
    @SaveAction
    @Operation(summary = "按项目EDIT权限新增工作项关联，不抓取外部地址")
    public Mono<WorkItemResponse> create(
            @PathVariable String versionId, @Valid @RequestBody WorkItemCreateRequest input) {
        return workItems.create(versionId, input);
    }

    @PutMapping("/{id}")
    @SaveAction
    @Operation(summary = "并发版本校验后更新工作项关联")
    public Mono<WorkItemResponse> update(
            @PathVariable String versionId,
            @PathVariable String id,
            @Valid @RequestBody WorkItemUpdateRequest input) {
        return workItems.update(versionId, id, input);
    }

    @PostMapping("/{id}/_delete")
    @SaveAction
    @Operation(summary = "并发版本校验后删除关联，不删除外部工作项或原型")
    public Mono<Boolean> delete(
            @PathVariable String versionId,
            @PathVariable String id,
            @Valid @RequestBody WorkItemDeleteRequest input) {
        return workItems.delete(versionId, id, input);
    }
}
