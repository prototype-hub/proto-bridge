package com.gaiys.proto.prototypehub.web;

import com.gaiys.proto.prototypehub.service.PrototypeElementDescriptionService;
import com.gaiys.proto.prototypehub.web.request.ElementDescriptionRequests;
import com.gaiys.proto.prototypehub.web.response.ElementDescriptionResponse;
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

/** 菜单query/save与项目READ/EDIT取交集；分享与guest没有此入口。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "prototype-hub-version", name = "原型版本管理")
@Tag(name = "区域人工功能说明")
@RequestMapping("/prototype-hub/versions/{versionId}/element-descriptions")
public class PrototypeElementDescriptionController {
    private final PrototypeElementDescriptionService descriptions;

    @GetMapping("/{id}")
    @QueryAction
    @Operation(summary = "按固定范围读取说明，供并发冲突后显式重读")
    public Mono<ElementDescriptionResponse> detail(
            @PathVariable String versionId,
            @PathVariable String id,
            @Valid ElementDescriptionRequests.Scope input) {
        return descriptions.detail(versionId, id, input);
    }

    @PostMapping("/_query")
    @QueryAction
    @Operation(summary = "分页读取当前固定页的人工功能说明")
    public Mono<PagerResult<ElementDescriptionResponse>> query(
            @PathVariable String versionId,
            @Valid @RequestBody ElementDescriptionRequests.Query input) {
        return descriptions.query(versionId, input);
    }

    @PostMapping
    @SaveAction
    @Operation(summary = "新建当前固定页的人工功能说明")
    public Mono<ElementDescriptionResponse> create(
            @PathVariable String versionId,
            @Valid @RequestBody ElementDescriptionRequests.Create input) {
        return descriptions.create(versionId, input);
    }

    @PutMapping("/{id}")
    @SaveAction
    @Operation(summary = "并发版本校验后完整更新人工功能说明")
    public Mono<ElementDescriptionResponse> update(
            @PathVariable String versionId,
            @PathVariable String id,
            @Valid @RequestBody ElementDescriptionRequests.Update input) {
        return descriptions.update(versionId, id, input);
    }

    @PostMapping("/{id}/_delete")
    @SaveAction
    @Operation(summary = "并发版本校验后删除说明，不删除原型或其他记录")
    public Mono<Boolean> delete(
            @PathVariable String versionId,
            @PathVariable String id,
            @Valid @RequestBody ElementDescriptionRequests.Delete input) {
        return descriptions.delete(versionId, id, input);
    }
}
