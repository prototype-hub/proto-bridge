package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypePageDeliveryService;
import com.gaiys.proto.prototypehub.web.request.PageDeliveryRequests;
import com.gaiys.proto.prototypehub.web.response.PageDeliveryResponse;
import com.gaiys.proto.prototypehub.web.response.ReviewAssigneeResponse;
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

/** 内部成员的固定页面交付入口；动作权限与项目READ/EDIT相交，候选不依赖成员管理权限。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "固定页面人工交付状态")
@RequestMapping("/prototype-hub/versions/{versionId}/page-deliveries")
public class PrototypePageDeliveryController {
    private final PrototypePageDeliveryService deliveries;

    @GetMapping("/{pageId}")
    @QueryAction
    @Operation(summary = "读取固定页面交付状态，旧页面明确返回未设置")
    public Mono<PageDeliveryResponse> detail(
            @PathVariable String versionId,
            @PathVariable String pageId,
            @Valid PageDeliveryRequests.Scope input) {
        return deliveries.detail(versionId, pageId, input);
    }

    @PutMapping("/{pageId}")
    @SaveAction
    @Operation(summary = "按当前项目EDIT与CAS版本保存人工状态、负责人及理由")
    public Mono<PageDeliveryResponse> save(
            @PathVariable String versionId,
            @PathVariable String pageId,
            @Valid @RequestBody PageDeliveryRequests.Save input) {
        return deliveries.save(versionId, pageId, input);
    }

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "按固定修订分页查询已设置交付状态的页面")
    public Mono<PagerResult<PageDeliveryResponse>> query(
            @PathVariable String versionId, @Valid @RequestBody PageDeliveryRequests.Query input) {
        return deliveries.query(versionId, input);
    }

    @PostMapping("/_candidates")
    @SaveAction
    @Operation(summary = "仅当前编辑路径分页查找活跃项目成员，不返回全局账号")
    public Mono<PagerResult<ReviewAssigneeResponse>> candidates(
            @PathVariable String versionId,
            @Valid @RequestBody PageDeliveryRequests.Candidates input) {
        return deliveries.candidates(versionId, input);
    }
}
